#!/usr/bin/env node
/*
 * Multi-tranche ladder market-making replay for BOND-style favorite tokens.
 *
 * Motivation: the target account has median 7.5 buys and 3 sells per asset,
 * reaches a median max inventory of ~7,000 shares, and unwinds to near zero.
 * That is inventory cycling / market making, not one-shot buy-and-hold.
 *
 * Model:
 * - Place several resting maker BUY levels below the reference price.
 * - Taker SELL flow fills them (queue factor applied).
 * - Each filled lot immediately gets a resting maker SELL at entry + target.
 * - Taker BUY flow fills the sell; the buy level is re-armed.
 * - Remaining inventory settles at 0/1.
 *
 * This is still a trade-print approximation, not a full order-book simulation.
 */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function numArg(name, fallback) { const v = Number(arg(name, fallback)); if (!Number.isFinite(v)) throw new Error('invalid --' + name); return v; }
function listArg(name, fallback) { return String(arg(name, fallback)).split(',').map(Number).filter(Number.isFinite); }
const datasetPath = arg('dataset'); const outputPath = arg('output');
if (!datasetPath || !outputPath) { console.error('required: --dataset --output'); process.exit(2); }
const horizonDays = numArg('horizon-days', 7);
const trancheNotional = numArg('tranche-notional', 100);
const queueFactor = numArg('queue-factor', 2);
const maxTranches = numArg('max-tranches', 8);
const maxHoldingDays = numArg('max-holding-days', 0);
const minHoursToClose = numArg('min-hours-to-close', 0);
const maxHoursToClose = numArg('max-hours-to-close', horizonDays * 24);
const momentumMinCents = arg('entry-momentum-min-cents') == null ? null : numArg('entry-momentum-min-cents', -999);
const momentumMaxCents = arg('entry-momentum-max-cents') == null ? null : numArg('entry-momentum-max-cents', 999);
const momentumLookbackSeconds = numArg('entry-momentum-lookback-seconds', 86400);
const levelsCents = listArg('levels-cents', '0,0.5,1,2');
const targetsCents = listArg('sell-target-cents', '1,2,3');
const bands = listArg('bands', '0.9,0.95,0.97,0.99,1.0000001');
function bandLabel(low, high) {
  const format = value => value >= 1 ? '1.0' : String(value);
  return `${format(low)}-${format(high)}`;
}
// Both ladder legs are maker orders. Official protocol maker fee is 0 for every
// current category, so no fee term is applied anywhere in this replay.
function referenceAt(market, ts) {
  let l0=null,l1=null;
  for(const t of market.trades){ if(t.timestamp>ts||t.price<=0||t.price>=1) continue; if(t.outcomeIndex===0)l0=t; else if(t.outcomeIndex===1)l1=t; }
  const p0=l0?l0.price:(l1?1-l1.price:null), p1=l1?l1.price:(l0?1-l0.price:null);
  if(p0==null||p1==null) return null;
  return p0>=p1?{favorite:0,price:p0}:{favorite:1,price:p1};
}
function momentumDeltaCents(market, entryTs, favoriteIndex) {
  const cutoff = entryTs - momentumLookbackSeconds;
  const first = market.trades.find(t => t.outcomeIndex === favoriteIndex && t.timestamp >= cutoff && t.timestamp <= entryTs && t.price > 0 && t.price < 1);
  const current = referenceAt(market, entryTs);
  if (!first || !current || current.favorite !== favoriteIndex) return null;
  return (current.price - first.price) * 100.0;
}
function momentumPasses(market, entryTs, ref) {
  if (momentumMinCents == null && momentumMaxCents == null) return true;
  const delta = momentumDeltaCents(market, entryTs, ref.favorite);
  if (delta == null) return false;
  const min = momentumMinCents == null ? -999 : momentumMinCents;
  const max = momentumMaxCents == null ? 999 : momentumMaxCents;
  return delta >= min && delta <= max;
}
function roundDownToTick(v,t){ return Math.max(t, Math.floor(v/t+1e-9)*t); }
function roundUpToTick(v,t){ return Math.min(0.999, Math.max(t, Math.ceil(v/t-1e-9)*t)); }
function mean(a){return a.length?a.reduce((x,y)=>x+y,0)/a.length:0;}
function median(a){if(!a.length)return 0;const s=[...a].sort((x,y)=>x-y);const m=Math.floor(s.length/2);return s.length%2?s[m]:(s[m-1]+s[m])/2;}
function wilson(p,n,z=1.96){if(!n)return[0,0];const d=1+z*z/n,c=(p+z*z/(2*n))/d,h=z*Math.sqrt((p*(1-p)+z*z/(4*n))/n)/d;return[Math.max(0,c-h),Math.min(1,c+h)];}
function bootstrapCi(v,runs=3000){if(v.length<2)return[v[0]||0,v[0]||0];const m=[];for(let r=0;r<runs;r++){let s=0;for(let i=0;i<v.length;i++)s+=v[(Math.random()*v.length)|0];m.push(s/v.length);}m.sort((a,b)=>a-b);return[m[Math.floor(0.025*(m.length-1))],m[Math.floor(0.975*(m.length-1))]];}
function maxDrawdownUsd(lots){const o=[...lots].sort((a,b)=>a.exitTs-b.exitTs);let c=0,p=0,d=0;for(const t of o){c+=t.pnl;p=Math.max(p,c);d=Math.max(d,p-c);}return d;}
function summarizeLots(lots){
  if(!lots.length) return {lots:0,meanRoi:0,portfolioRoi:0,winRate:0,totalPnl:0,settleLossCount:0,worstRoi:0};
  const rois=lots.map(l=>l.roi), pnls=lots.map(l=>l.pnl);
  const deployed=lots.reduce((s,l)=>s+l.entryPrice*l.shares,0);
  return {
    lots:lots.length,
    meanRoi:mean(rois),
    portfolioRoi:deployed>0?pnls.reduce((a,b)=>a+b,0)/deployed:0,
    winRate:rois.filter(x=>x>0).length/rois.length,
    totalPnl:pnls.reduce((a,b)=>a+b,0),
    settleLossCount:lots.filter(l=>l.exitType==='SETTLE'&&l.pnl<0).length,
    worstRoi:Math.min(...rois)
  };
}
function summarizeByYear(lots){
  const groups={};
  for(const lot of lots){
    const year=new Date(lot.exitTs*1000).getUTCFullYear();
    (groups[year]??=[]).push(lot);
  }
  return Object.fromEntries(Object.entries(groups).sort(([a],[b])=>a.localeCompare(b)).map(([year,group])=>[year,summarizeLots(group)]));
}

function simulateMarket(market, ref, targetCents) {
  const entryTs = market.closedTimestamp - horizonDays*86400;
  const tick = market.tickSize > 0 ? market.tickSize : 0.001;
  const fav = ref.favorite;
  const trades = market.trades.filter(t=>t.outcomeIndex===fav && t.timestamp>entryTs && t.timestamp<market.closedTimestamp && t.price>0 && t.price<1 && t.size>0).sort((a,b)=>a.timestamp-b.timestamp);
  if(!trades.length) return {filled:false, lots:[], buys:0, sells:0, maxInventory:0, avgInventory:0};
  const won = fav === market.winnerIndex;

  // Ladder levels are below the reference price; level 0 is the touch.
  const levels = levelsCents.map(c => {
    const price = roundDownToTick(Math.max(0.001, ref.price - c/100), tick);
    return { offsetCents: c, price, shares: trancheNotional/price, queueVol: 0, filled: false };
  });
  // Deduplicate identical prices.
  const uniqueLevels = [];
  for (const l of levels) if (!uniqueLevels.some(x => Math.abs(x.price-l.price) < 1e-12)) uniqueLevels.push(l);

  const openLots = [];
  const closedLots = [];
  let buys = 0, sells = 0, inventoryShares = 0, inventorySum = 0, inventoryN = 0;
  let maxInventory = 0;

  for (const trade of trades) {
    const hoursToClose = (market.closedTimestamp - trade.timestamp) / 3600;
    const entryAllowed = hoursToClose >= minHoursToClose && hoursToClose <= maxHoursToClose;
    if (openLots.length >= maxTranches && trade.side?.toUpperCase() === 'SELL') {
      // inventory cap reached; do not fill more buys
    }
    let remaining = trade.size;
    const side = String(trade.side ?? '').toUpperCase();

    if (side === 'SELL' && entryAllowed) {
      // Fill buy levels from highest price down.
      const candidates = uniqueLevels.filter(l => !l.filled && l.price >= trade.price - 1e-9).sort((a,b)=>b.price-a.price);
      for (const level of candidates) {
        if (remaining <= 1e-12) break;
        if (openLots.length >= maxTranches) break;
        const need = Math.max(0, queueFactor*level.shares - level.queueVol);
        const take = Math.min(remaining, need);
        level.queueVol += take;
        remaining -= take;
        if (level.queueVol + 1e-9 >= queueFactor*level.shares) {
          level.filled = true;
          const lot = { entryTs: trade.timestamp, entryPrice: level.price, shares: level.shares, sellPrice: roundUpToTick(Math.min(0.999, level.price + targetCents/100), tick), sellQueueVol: 0, offsetCents: level.offsetCents };
          openLots.push(lot);
          buys++;
          inventoryShares += lot.shares;
          maxInventory = Math.max(maxInventory, inventoryShares);
        }
      }
    } else if (side === 'BUY') {
      // Fill sell orders from lowest target up.
      const candidates = [...openLots].filter(l => l.sellPrice <= trade.price + 1e-9).sort((a,b)=>a.sellPrice-b.sellPrice);
      for (const lot of candidates) {
        if (remaining <= 1e-12) break;
        const need = Math.max(0, queueFactor*lot.shares - lot.sellQueueVol);
        const take = Math.min(remaining, need);
        lot.sellQueueVol += take;
        remaining -= take;
        if (lot.sellQueueVol + 1e-9 >= queueFactor*lot.shares) {
          const pnl = (lot.sellPrice - lot.entryPrice)*lot.shares;
          closedLots.push({entryTs:lot.entryTs, entryPrice:lot.entryPrice, exitTs:trade.timestamp, exitPrice:lot.sellPrice, shares:lot.shares, pnl, exitType:'SELL', offsetCents:lot.offsetCents});
          const idx = openLots.indexOf(lot);
          if (idx >= 0) openLots.splice(idx,1);
          inventoryShares -= lot.shares;
          sells++;
          // Re-arm the same level.
          const level = uniqueLevels.find(l => Math.abs(l.price-lot.entryPrice) < 1e-12);
          if (level) {
            level.filled = !entryAllowed;
            level.queueVol = 0;
          }
        }
      }
    }
    inventorySum += inventoryShares; inventoryN++;
  }

  // Settle remaining inventory.
  for (const lot of openLots) {
    const payoff = won ? 1 : 0;
    const pnl = (payoff - lot.entryPrice)*lot.shares;
    closedLots.push({entryTs:lot.entryTs, entryPrice:lot.entryPrice, exitTs:market.closedTimestamp, exitPrice:payoff, shares:lot.shares, pnl, exitType:'SETTLE', offsetCents:lot.offsetCents});
  }

  const deployed = closedLots.reduce((s,l)=>s+l.entryPrice*l.shares,0);
  const pnl = closedLots.reduce((s,l)=>s+l.pnl,0);
  const maxInvNotional = maxInventory * ref.price;
  return {
    filled: closedLots.length > 0,
    lots: closedLots,
    buys, sells,
    maxInventory,
    maxInventoryNotional: maxInvNotional,
    avgInventory: inventoryN ? inventorySum/inventoryN : 0,
    deployed,
    pnl,
    roi: deployed > 0 ? pnl/deployed : 0
  };
}

const dataset=JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const refs=[];
for(const m of dataset.markets){
  const entryTs=m.closedTimestamp-horizonDays*86400;
  const r=referenceAt(m,entryTs);
  if(r) refs.push({m,r,entryTs});
}
const results=[];
for(let bi=0;bi<bands.length-1;bi++){
  const low=bands[bi], high=bands[bi+1]; if(low==null||high==null) continue;
  const priceEligible=refs.filter(({r})=>r.price>=low&&r.price<high);
  const eligible=priceEligible.filter(({m,r,entryTs})=>momentumPasses(m,entryTs,r));
  const tags=[...new Set(eligible.map(({m})=>m.tag))].sort();
  for(const tag of tags){
    for(const target of targetsCents){
      const allLots=[]; let eligibleCount=0, filledMarkets=0, totalBuys=0, totalSells=0, maxInvNotionalSum=0, avgInvSum=0, maxLotsPerMarket=0, marketsOver10Lots=0, marketsOver20Lots=0;
      for(const {m,r} of eligible){
        if(m.tag!==tag) continue;
        eligibleCount++;
        const sim=simulateMarket(m,r,target);
        if(sim.filled){ filledMarkets++; totalBuys+=sim.buys; totalSells+=sim.sells; maxInvNotionalSum+=sim.maxInventoryNotional; avgInvSum+=sim.avgInventory; const marketLots=sim.lots.length; if(marketLots>maxLotsPerMarket)maxLotsPerMarket=marketLots; if(marketLots>10)marketsOver10Lots++; if(marketLots>20)marketsOver20Lots++; }
        for(const l of sim.lots) allLots.push({...l, roi:l.pnl/trancheNotional, tag:m.tag, conditionId:m.conditionId});
      }
      const rois=allLots.map(l=>l.roi), pnls=allLots.map(l=>l.pnl);
      const wins=rois.filter(x=>x>0).length, losses=rois.filter(x=>x<0).length;
      const aw=mean(rois.filter(x=>x>0)), al=mean(rois.filter(x=>x<0));
      const lr=allLots.length?losses/allLots.length:0;
      const deployed=allLots.reduce((s,l)=>s+l.entryPrice*l.shares,0);
      results.push({
        band:bandLabel(low, high), bandLow:low, bandHigh:high, tag, targetCents:target, momentumMinCents, momentumMaxCents, momentumLookbackSeconds, levelsCents:levelsCents.join(','), trancheNotional, queueFactor, maxTranches, minHoursToClose, maxHoursToClose,
        eligible:eligibleCount, filledMarkets, fillRate:eligibleCount?filledMarkets/eligibleCount:0,
        lots:allLots.length, buys:totalBuys, sells:totalSells,
        avgBuysPerFilledMarket:filledMarkets?totalBuys/filledMarkets:0,
        avgSellsPerFilledMarket:filledMarkets?totalSells/filledMarkets:0,
        avgMaxInventoryNotional:filledMarkets?maxInvNotionalSum/filledMarkets:0,
        avgInventoryShares:filledMarkets?avgInvSum/filledMarkets:0, maxLotsPerMarket, marketsOver10Lots, marketsOver20Lots,
        wins, losses, winRate:allLots.length?wins/allLots.length:0,
        meanRoi:mean(rois), medianRoi:median(rois), portfolioRoi:deployed>0?pnls.reduce((a,b)=>a+b,0)/deployed:0,
        roiCi95:bootstrapCi(rois), worstRoi:rois.length?Math.min(...rois):0,
        avgWin:aw, avgLoss:al, breakEvenLossRate:aw>0&&al<0?aw/(aw+Math.abs(al)):null,
        lossRate:lr, lossRateCi95:wilson(lr,allLots.length),
        sellCount:allLots.filter(l=>l.exitType==='SELL').length,
        settleCount:allLots.filter(l=>l.exitType==='SETTLE').length,
        settleLossCount:allLots.filter(l=>l.exitType==='SETTLE'&&l.pnl<0).length,
        totalPnl:pnls.reduce((a,b)=>a+b,0), deployed,
        maxDrawdownUsd:maxDrawdownUsd(allLots),
        byYear:summarizeByYear(allLots)
      });
    }
  }
}
fs.writeFileSync(outputPath,JSON.stringify({generatedAt:new Date().toISOString(),datasetPath,horizonDays,trancheNotional,queueFactor,maxTranches,levelsCents,targetsCents,momentumMinCents,momentumMaxCents,momentumLookbackSeconds,results},null,2));
console.log('band tag tgt levels elig filled fill% lots buys sells win% meanRoi roiCI worst settleLoss maxInv$');
for(const r of results){
  console.log([r.band.padEnd(9),r.tag.padEnd(12),String(r.targetCents).padStart(3),String(r.levelsCents).padStart(9),String(r.eligible).padStart(4),String(r.filledMarkets).padStart(5),(100*r.fillRate).toFixed(1).padStart(5),String(r.lots).padStart(4),String(r.buys).padStart(4),String(r.sells).padStart(4),(100*r.winRate).toFixed(1).padStart(5),(100*r.meanRoi).toFixed(2).padStart(7),`[${(100*r.roiCi95[0]).toFixed(2)},${(100*r.roiCi95[1]).toFixed(2)}]`.padStart(15),(100*r.worstRoi).toFixed(1).padStart(7),String(r.settleLossCount).padStart(3),r.avgMaxInventoryNotional.toFixed(0).padStart(7)].join(' '));
}
console.log('wrote '+outputPath);