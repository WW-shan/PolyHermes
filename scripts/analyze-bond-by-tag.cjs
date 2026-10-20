#!/usr/bin/env node
/* All-eligible BOND model, grouped by market tag. */
const fs = require('fs');
const zlib = require('zlib');

function arg(name, fallback) { const i = process.argv.indexOf('--' + name); return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback; }
function numArg(name, fallback) { const v = Number(arg(name, fallback)); if (!Number.isFinite(v)) throw new Error('invalid --' + name); return v; }
const datasetPath = arg('dataset'); const outputPath = arg('output');
if (!datasetPath || !outputPath) { console.error('required: --dataset --output'); process.exit(2); }
const horizonDays = numArg('horizon-days', 7); const notional = numArg('notional', 100);
const BANDS = [['0.9-0.95',0.9,0.95],['0.95-0.97',0.95,0.97],['0.97-0.99',0.97,0.99],['0.99-1.0',0.99,1.0000001]];
const TARGETS = [1,2,3];
function referenceAt(market, ts) {
  let l0=null,l1=null;
  for (const t of market.trades) { if (t.timestamp>ts||t.price<=0||t.price>=1) continue; if(t.outcomeIndex===0)l0=t; else if(t.outcomeIndex===1)l1=t; }
  const p0=l0?l0.price:(l1?1-l1.price:null), p1=l1?l1.price:(l0?1-l0.price:null);
  if(p0==null||p1==null) return null;
  return p0>=p1 ? {favorite:0,price:p0} : {favorite:1,price:p1};
}
function exitFor(market,fav,entryTs,entryPrice,targetCents,won) {
  const target=Math.min(0.999,entryPrice+targetCents/100);
  for(const t of market.trades){ if(t.timestamp<=entryTs||t.outcomeIndex!==fav||t.price<=0||t.price>=1) continue; if(t.price>=target) return {price:target,type:'SELL',ts:t.timestamp}; }
  return {price:won?1:0,type:'SETTLE',ts:market.closedTimestamp};
}
function mean(a){return a.length?a.reduce((x,y)=>x+y,0)/a.length:0;}
function median(a){if(!a.length)return 0;const s=[...a].sort((x,y)=>x-y);const m=Math.floor(s.length/2);return s.length%2?s[m]:(s[m-1]+s[m])/2;}
function wilson(p,n,z=1.96){if(!n)return[0,0];const d=1+z*z/n,c=(p+z*z/(2*n))/d,h=z*Math.sqrt((p*(1-p)+z*z/(4*n))/n)/d;return[Math.max(0,c-h),Math.min(1,c+h)];}
function bootstrapCi(v,runs=3000){if(v.length<2)return[v[0]||0,v[0]||0];const m=[];for(let r=0;r<runs;r++){let s=0;for(let i=0;i<v.length;i++)s+=v[(Math.random()*v.length)|0];m.push(s/v.length);}m.sort((a,b)=>a-b);return[m[Math.floor(0.025*(m.length-1))],m[Math.floor(0.975*(m.length-1))]];}

const dataset=JSON.parse(zlib.gunzipSync(fs.readFileSync(datasetPath)).toString('utf8'));
const h=horizonDays*86400;
const refs=[];
for(const m of dataset.markets){const r=referenceAt(m,m.closedTimestamp-h);if(r)refs.push({m,r});}
const results=[];
for(const [label,low,high] of BANDS){
  const eligible=refs.filter(({r})=>r.price>=low&&r.price<high);
  const tags=[...new Set(eligible.map(({m})=>m.tag))].sort();
  for(const tag of tags){
    for(const target of TARGETS){
      const trades=[];
      for(const {m,r} of eligible){
        if(m.tag!==tag) continue;
        const won=r.favorite===m.winnerIndex;
        const x=exitFor(m,r.favorite,m.closedTimestamp-h,r.price,target,won);
        const shares=notional/r.price;
        const pnl=(x.price-r.price)*shares;
        trades.push({roi:pnl/notional,pnl,type:x.type});
      }
      const rois=trades.map(t=>t.roi); const pnls=trades.map(t=>t.pnl);
      const wins=rois.filter(x=>x>0).length, losses=rois.filter(x=>x<0).length;
      const aw=mean(rois.filter(x=>x>0)), al=mean(rois.filter(x=>x<0));
      const lr=trades.length?losses/trades.length:0;
      results.push({band:label,tag,targetCents:target,n:trades.length,wins,losses,winRate:trades.length?wins/trades.length:0,meanRoi:mean(rois),medianRoi:median(rois),portfolioRoi:trades.length?pnls.reduce((a,b)=>a+b,0)/(trades.length*notional):0,roiCi95:bootstrapCi(rois),worstRoi:rois.length?Math.min(...rois):0,avgWin:aw,avgLoss:al,breakEvenLossRate:aw>0&&al<0?aw/(aw+Math.abs(al)):null,lossRate:lr,lossRateCi95:wilson(lr,trades.length),sellCount:trades.filter(t=>t.type==='SELL').length,settleCount:trades.filter(t=>t.type==='SETTLE').length,settleLossCount:trades.filter(t=>t.type==='SETTLE'&&t.pnl<0).length});
    }
  }
}
fs.writeFileSync(outputPath,JSON.stringify({generatedAt:new Date().toISOString(),datasetPath,horizonDays,notional,results},null,2));
console.log('band tag target n win% meanRoi roiCI portfolioRoi worst lossRate lossCI');
for(const r of results){console.log([r.band.padEnd(9),r.tag.padEnd(12),String(r.targetCents).padStart(2),String(r.n).padStart(4),(100*r.winRate).toFixed(1).padStart(5),(100*r.meanRoi).toFixed(2).padStart(7),`[${(100*r.roiCi95[0]).toFixed(2)},${(100*r.roiCi95[1]).toFixed(2)}]`.padStart(15),(100*r.portfolioRoi).toFixed(2).padStart(7),(100*r.worstRoi).toFixed(1).padStart(7),(100*r.lossRate).toFixed(1).padStart(5),`[${(100*r.lossRateCi95[0]).toFixed(1)},${(100*r.lossRateCi95[1]).toFixed(1)}]`.padStart(11)].join(' '));}
console.log('wrote '+outputPath);