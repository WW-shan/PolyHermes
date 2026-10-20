#!/usr/bin/env node
/*
 * Queue-constrained BOND_SCALP replay, grouped by market tag, with optional stop-loss.
 * Mirrors EventMakerBacktestEngine's maker fill logic:
 * - resting maker BUY at last favorite print - entryOffset, placed at entry timestamp
 * - only taker SELL prints at/below the limit accumulate queue volume
 * - fill after cumulative volume >= queueFactor * order shares
 * - resting maker SELL at entry + exitSpread, only taker BUY prints accumulate
 * - otherwise settle at market close
 *
 * Stop-loss (optional): taker SELL at first print <= entry - stopCents (plus slippage).
 */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function numArg(name, fallback) { const v = Number(arg(name, fallback)); if (!Number.isFinite(v)) throw new Error('invalid --' + name); return v; }
function listArg(name, fallback) { return String(arg(name, fallback)).split(',').map(Number).filter(Number.isFinite); }
const datasetPath = arg('dataset'); const outputPath = arg('output');
if (!datasetPath || !outputPath) { console.error('required: --dataset --output'); process.exit(2); }
const horizonDays = numArg('horizon-days', 7);
const notional = numArg('notional', 100);
const entryOffset = numArg('entry-offset-cents', 0);
const queueFactor = numArg('queue-factor', 2);
const maxCycles = numArg('max-cycles', 3);
const requoteSeconds = numArg('requote-seconds', 300);
const maxOrderAgeSeconds = numArg('max-order-age-seconds', 1800);
const stopSlippageTicks = numArg('stop-slippage-ticks', 1);
const stopConfirmSeconds = numArg('stop-confirm-seconds', 0);
const maxHoldingDays = numArg('max-holding-days', 0);
const flowExitWindowSeconds = numArg('flow-exit-window-seconds', 0);
const flowExitMaxSignedFlow = numArg('flow-exit-max-signed-flow', -0.4);
const flowExitMinPriceDropCents = numArg('flow-exit-min-price-drop-cents', 0.5);
const flowExitMinHoldingSeconds = numArg('flow-exit-min-holding-seconds', 300);
// NOTE: the current 7d dataset only contains ~0.7-0.9h of pre-entry trades,
// so a 24h momentum filter is usually empty. Use a longer dataset to test it.
const entryMomentumMinCents = numArg('entry-momentum-min-cents', -999);
const entryMomentumMaxCents = numArg('entry-momentum-max-cents', 999);
const entryMomentumLookbackSeconds = numArg('entry-momentum-lookback-seconds', 86400);
const defaultTakerFee = numArg('default-taker-fee', 0.04);
const bands = listArg('bands', '0.9,0.95,0.97,0.99,1.0000001');
const targets = listArg('targets', '1,3');
const stops = listArg('stops', '0,5,10,15');
const BAND_LABELS = ['0.9-0.95','0.95-0.97','0.97-0.99','0.99-1.0'];
const TAKER_FEE_BY_TAG = { Politics: 0.04, Geopolitics: 0.0 };
function takerFeeRate(tag){ return Object.prototype.hasOwnProperty.call(TAKER_FEE_BY_TAG,tag)?TAKER_FEE_BY_TAG[tag]:defaultTakerFee; }
function referenceAt(market, ts) {
  let l0=null,l1=null;
  for(const t of market.trades){ if(t.timestamp>ts||t.price<=0||t.price>=1) continue; if(t.outcomeIndex===0)l0=t; else if(t.outcomeIndex===1)l1=t; }
  const p0=l0?l0.price:(l1?1-l1.price:null), p1=l1?l1.price:(l0?1-l0.price:null);
  if(p0==null||p1==null) return null;
  return p0>=p1?{favorite:0,price:p0}:{favorite:1,price:p1};
}
function roundDownToTick(v,t){ return Math.max(t, Math.floor(v/t+1e-9)*t); }
function roundUpToTick(v,t){ return Math.min(0.999, Math.max(t, Math.ceil(v/t-1e-9)*t)); }
function mean(a){return a.length?a.reduce((x,y)=>x+y,0)/a.length:0;}
function median(a){if(!a.length)return 0;const s=[...a].sort((x,y)=>x-y);const m=Math.floor(s.length/2);return s.length%2?s[m]:(s[m-1]+s[m])/2;}
function wilson(p,n,z=1.96){if(!n)return[0,0];const d=1+z*z/n,c=(p+z*z/(2*n))/d,h=z*Math.sqrt((p*(1-p)+z*z/(4*n))/n)/d;return[Math.max(0,c-h),Math.min(1,c+h)];}
function bootstrapCi(v,runs=3000){if(v.length<2)return[v[0]||0,v[0]||0];const m=[];for(let r=0;r<runs;r++){let s=0;for(let i=0;i<v.length;i++)s+=v[(Math.random()*v.length)|0];m.push(s/v.length);}m.sort((a,b)=>a-b);return[m[Math.floor(0.025*(m.length-1))],m[Math.floor(0.975*(m.length-1))]];}
function maxDrawdownUsd(trades){const o=[...trades].sort((a,b)=>a.exitTs-b.exitTs);let c=0,p=0,d=0;for(const t of o){c+=t.pnl;p=Math.max(p,c);d=Math.max(d,p-c);}return d;}

function simulateMarket(market, ref, targetCents, stopCents, minPrice, maxPrice) {
  const entryTs = market.closedTimestamp - horizonDays*86400;
  const fav = ref.favorite;
  const tick = market.tickSize > 0 ? market.tickSize : 0.001;
  const favTrades = market.trades.filter(t=>t.outcomeIndex===fav && t.timestamp<market.closedTimestamp && t.price>0 && t.price<1 && t.size>0).sort((a,b)=>a.timestamp-b.timestamp);
  const trades = favTrades.filter(t=>t.timestamp>entryTs);
  const tradeOffset = favTrades.length - trades.length;
  const buyPrefix = new Array(favTrades.length + 1).fill(0);
  const sellPrefix = new Array(favTrades.length + 1).fill(0);
  for (let i = 0; i < favTrades.length; i++) {
    const t = favTrades[i];
    buyPrefix[i + 1] = buyPrefix[i] + (String(t.side ?? '').toUpperCase() === 'BUY' ? t.size : 0);
    sellPrefix[i + 1] = sellPrefix[i] + (String(t.side ?? '').toUpperCase() === 'SELL' ? t.size : 0);
  }
  function lowerBoundTimestamp(target) {
    let low = 0, high = favTrades.length;
    while (low < high) { const mid = (low + high) >> 1; if (favTrades[mid].timestamp < target) low = mid + 1; else high = mid; }
    return low;
  }
  const won = fav === market.winnerIndex;
  if(!trades.length) return {filled:false, cycles:[]};
  const cycles=[];
  let inventory=null, buyOrder=null, sellPrice=null, sellPlacedTs=null, sellVol=0, stoppedOut=false;

  function placeBuy(atPrice, ts) {
    const limit = roundDownToTick(Math.max(0.001, atPrice - entryOffset/100), tick);
    buyOrder = { limit, shares: notional/limit, placedTs: ts, vol: 0 };
  }
  placeBuy(ref.price, entryTs);

  for(let ti = 0; ti < trades.length; ti++) {
    const trade = trades[ti];
    if(cycles.length >= maxCycles && !inventory && !buyOrder) break;
    if(stoppedOut) break;
    if(!inventory) {
      if(!buyOrder) { if(trade.price>=minPrice && trade.price<maxPrice) placeBuy(trade.price, trade.timestamp); continue; }
      // max-age cancellation (no same-print replacement)
      if(trade.timestamp - buyOrder.placedTs >= maxOrderAgeSeconds) { buyOrder = null; continue; }
      // requote only when price is still in band and the limit actually changes
      if(requoteSeconds > 0 && trade.timestamp - buyOrder.placedTs >= requoteSeconds) {
        const nextLimit = roundDownToTick(Math.max(0.001, trade.price - entryOffset/100), tick);
        if(trade.price>=minPrice && trade.price<maxPrice && Math.abs(nextLimit-buyOrder.limit)>1e-12) { placeBuy(trade.price, trade.timestamp); continue; }
      }
      if(trade.side && trade.side.toUpperCase()==='SELL' && trade.price <= buyOrder.limit + 1e-9) {
        buyOrder.vol += trade.size;
        if(buyOrder.vol + 1e-9 >= queueFactor * buyOrder.shares) {
          inventory = { entryTs: trade.timestamp, entryPrice: buyOrder.limit, shares: buyOrder.shares, stopSince: null };
          sellPrice = roundUpToTick(Math.min(0.999, buyOrder.limit + targetCents/100), tick);
          sellPlacedTs = trade.timestamp; sellVol = 0; buyOrder = null;
        }
      }
    } else {
      // adverse-flow exit: proxy for the leader's discretionary "thesis broken" exit
      if(flowExitWindowSeconds > 0 && trade.timestamp - inventory.entryTs >= flowExitMinHoldingSeconds) {
        const gi = tradeOffset + ti;
        const from = lowerBoundTimestamp(trade.timestamp - flowExitWindowSeconds);
        const to = gi + 1;
        if (from < to) {
          const buyVol = buyPrefix[to] - buyPrefix[from];
          const sellVol = sellPrefix[to] - sellPrefix[from];
          const known = buyVol + sellVol;
          if (known > 1e-12) {
            const signedFlow = (buyVol - sellVol) / known;
            const priceDropCents = (favTrades[from].price - trade.price) * 100;
            if (signedFlow <= flowExitMaxSignedFlow + 1e-9 && priceDropCents + 1e-9 >= flowExitMinPriceDropCents) {
              const exitPrice = Math.max(0.001, trade.price - tick*stopSlippageTicks);
              const fee = inventory.shares * takerFeeRate(market.tag) * exitPrice * (1-exitPrice);
              const pnl = (exitPrice - inventory.entryPrice)*inventory.shares - fee;
              cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:trade.timestamp, exitPrice, exitType:'FLOW', pnl, fee, holdingSeconds:Math.max(0,trade.timestamp-inventory.entryTs)});
              inventory=null; stoppedOut=true; break;
            }
          }
        }
      }
      // time-based exit: realize a small loss instead of holding to a rare settlement wipeout
      if(maxHoldingDays > 0 && trade.timestamp - inventory.entryTs >= maxHoldingDays*86400) {
        const exitPrice = Math.max(0.001, trade.price - tick*stopSlippageTicks);
        const fee = inventory.shares * takerFeeRate(market.tag) * exitPrice * (1-exitPrice);
        const pnl = (exitPrice - inventory.entryPrice)*inventory.shares - fee;
        cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:trade.timestamp, exitPrice, exitType:'TIME', pnl, fee, holdingSeconds:Math.max(0,trade.timestamp-inventory.entryTs)});
        inventory=null; stoppedOut=true; break;
      }
      // stop-loss first
      if(stopCents > 0) {
        const stopPrice = Math.max(0.001, inventory.entryPrice - stopCents/100);
        if(trade.price <= stopPrice + 1e-9) {
          if (stopConfirmSeconds <= 0) {
            const exitPrice = Math.max(0.001, trade.price - tick*stopSlippageTicks);
            const fee = inventory.shares * takerFeeRate(market.tag) * exitPrice * (1-exitPrice);
            const pnl = (exitPrice - inventory.entryPrice)*inventory.shares - fee;
            cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:trade.timestamp, exitPrice, exitType:'STOP', pnl, fee, holdingSeconds:Math.max(0,trade.timestamp-inventory.entryTs)});
            inventory=null; stoppedOut=true; break;
          }
          if (inventory.stopSince == null) inventory.stopSince = trade.timestamp;
          if (trade.timestamp - inventory.stopSince >= stopConfirmSeconds) {
            const exitPrice = Math.max(0.001, trade.price - tick*stopSlippageTicks);
            const fee = inventory.shares * takerFeeRate(market.tag) * exitPrice * (1-exitPrice);
            const pnl = (exitPrice - inventory.entryPrice)*inventory.shares - fee;
            cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:trade.timestamp, exitPrice, exitType:'STOP', pnl, fee, holdingSeconds:Math.max(0,trade.timestamp-inventory.entryTs)});
            inventory=null; stoppedOut=true; break;
          }
        } else {
          inventory.stopSince = null;
        }
      }
      const sellAge = trade.timestamp - (sellPlacedTs ?? inventory.entryTs);
      if(sellAge >= maxOrderAgeSeconds) { sellPlacedTs = trade.timestamp; sellVol = 0; continue; }
      if(trade.side && trade.side.toUpperCase()==='BUY' && trade.price >= sellPrice - 1e-9) {
        sellVol += trade.size;
        if(sellVol + 1e-9 >= queueFactor * inventory.shares) {
          const pnl = (sellPrice - inventory.entryPrice)*inventory.shares;
          cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:trade.timestamp, exitPrice:sellPrice, exitType:'SELL', pnl, fee:0, holdingSeconds:Math.max(0,trade.timestamp-inventory.entryTs)});
          inventory=null; sellPrice=null; sellPlacedTs=null; sellVol=0;
          if(cycles.length < maxCycles && trade.price>=minPrice && trade.price<maxPrice) placeBuy(trade.price, trade.timestamp);
        }
      }
    }
  }
  if(inventory) {
    const payoff = won?1:0;
    const pnl = (payoff - inventory.entryPrice)*inventory.shares;
    cycles.push({entryTs:inventory.entryTs, entryPrice:inventory.entryPrice, shares:inventory.shares, exitTs:market.closedTimestamp, exitPrice:payoff, exitType:'SETTLE', pnl, fee:0, holdingSeconds:Math.max(0,market.closedTimestamp-inventory.entryTs)});
  }
  return {filled:cycles.length>0, cycles};
}

const dataset=JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const refs=[];
function priceForOutcomeNear(market, outcomeIndex, ts) {
  let before = null, after = null;
  for (const t of market.trades) {
    if (t.outcomeIndex !== outcomeIndex || t.price <= 0 || t.price >= 1) continue;
    if (t.timestamp <= ts) { if (!before || t.timestamp >= before.timestamp) before = t; }
    else if (!after || t.timestamp < after.timestamp) after = t;
  }
  return before ? before.price : (after ? after.price : null);
}
for(const m of dataset.markets){
  const entryTs = m.closedTimestamp - horizonDays*86400;
  const r = referenceAt(m, entryTs);
  if(!r) continue;
  const prevPrice = priceForOutcomeNear(m, r.favorite, entryTs - entryMomentumLookbackSeconds);
  refs.push({m, r, momentum24hCents: prevPrice != null ? (r.price - prevPrice) * 100 : null});
}
const results=[];
for(let bi=0;bi<BAND_LABELS.length;bi++){
  const low=bands[bi], high=bands[bi+1]; if(low==null||high==null) continue;
  const momentumFilterActive = entryMomentumMinCents > -900 || entryMomentumMaxCents < 900;
  const eligible=refs.filter(({r,momentum24hCents})=>{
    if(r.price<low || r.price>=high) return false;
    if(!momentumFilterActive) return true;
    return momentum24hCents != null && momentum24hCents >= entryMomentumMinCents && momentum24hCents < entryMomentumMaxCents;
  });
  const tags=[...new Set(eligible.map(({m})=>m.tag))].sort();
  for(const tag of tags){
    for(const target of targets){
      for(const stop of stops){
        const trades=[]; let eligibleCount=0;
        for(const {m,r} of eligible){
          if(m.tag!==tag) continue;
          eligibleCount++;
          const sim=simulateMarket(m,r,target,stop,low,high);
          for(const c of sim.cycles) trades.push({...c, pnl:c.pnl, roi:c.pnl/notional, tag:m.tag, conditionId:m.conditionId});
        }
        const rois=trades.map(t=>t.roi), pnls=trades.map(t=>t.pnl);
        const wins=rois.filter(x=>x>0).length, losses=rois.filter(x=>x<0).length;
        const aw=mean(rois.filter(x=>x>0)), al=mean(rois.filter(x=>x<0));
        const lr=trades.length?losses/trades.length:0;
        results.push({band:BAND_LABELS[bi],tag,targetCents:target,stopCents:stop,eligible:eligibleCount,filledMarkets:new Set(trades.map(t=>t.conditionId)).size,cycles:trades.length,fillRate:eligibleCount?new Set(trades.map(t=>t.conditionId)).size/eligibleCount:0,wins,losses,winRate:trades.length?wins/trades.length:0,meanRoi:mean(rois),medianRoi:median(rois),portfolioRoi:trades.length?pnls.reduce((a,b)=>a+b,0)/(trades.length*notional):0,roiCi95:bootstrapCi(rois),worstRoi:rois.length?Math.min(...rois):0,avgWin:aw,avgLoss:al,breakEvenLossRate:aw>0&&al<0?aw/(aw+Math.abs(al)):null,lossRate:lr,lossRateCi95:wilson(lr,trades.length),sellCount:trades.filter(t=>t.exitType==='SELL').length,stopCount:trades.filter(t=>t.exitType==='STOP').length,timeExitCount:trades.filter(t=>t.exitType==='TIME').length,flowExitCount:trades.filter(t=>t.exitType==='FLOW').length,settleCount:trades.filter(t=>t.exitType==='SETTLE').length,settleLossCount:trades.filter(t=>t.exitType==='SETTLE'&&t.pnl<0).length,avgHoldingDays:trades.length?mean(trades.map(t=>t.holdingSeconds))/86400:0,maxDrawdownUsd:maxDrawdownUsd(trades),totalFees:trades.reduce((a,b)=>a+b.fee,0)});
      }
    }
  }
}
fs.writeFileSync(outputPath,JSON.stringify({generatedAt:new Date().toISOString(),datasetPath,horizonDays,notional,entryOffset,queueFactor,maxCycles,requoteSeconds,maxOrderAgeSeconds,stopSlippageTicks,stopConfirmSeconds,maxHoldingDays,flowExitWindowSeconds,flowExitMaxSignedFlow,flowExitMinPriceDropCents,flowExitMinHoldingSeconds,entryMomentumMinCents,entryMomentumMaxCents,entryMomentumLookbackSeconds,results},null,2));
console.log('band tag tgt stop elig filled fill% cyc win% meanRoi roiCI worst lossRate lossCI stop settleLoss');
for(const r of results){console.log([r.band.padEnd(9),r.tag.padEnd(12),String(r.targetCents).padStart(3),String(r.stopCents).padStart(3),String(r.eligible).padStart(4),String(r.filledMarkets).padStart(4),(100*r.fillRate).toFixed(1).padStart(5),String(r.cycles).padStart(3),(100*r.winRate).toFixed(1).padStart(5),(100*r.meanRoi).toFixed(2).padStart(7),`[${(100*r.roiCi95[0]).toFixed(2)},${(100*r.roiCi95[1]).toFixed(2)}]`.padStart(15),(100*r.worstRoi).toFixed(1).padStart(7),(100*r.lossRate).toFixed(1).padStart(5),`[${(100*r.lossRateCi95[0]).toFixed(1)},${(100*r.lossRateCi95[1]).toFixed(1)}]`.padStart(11),String(r.stopCount).padStart(4),String(r.settleLossCount).padStart(3)].join(' '));}
console.log('wrote '+outputPath);