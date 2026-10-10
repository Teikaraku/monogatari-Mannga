// Import the exact HoshiDicts revision pinned by the original Xcode project.
// Upstream: https://github.com/Manhhao/hoshidicts/tree/bc62d2becc3c2dac0f6abd1cbaa1df11b1ea1acc
import fs from 'node:fs';
import path from 'node:path';
const revision='bc62d2becc3c2dac0f6abd1cbaa1df11b1ea1acc';
const base=`https://raw.githubusercontent.com/Manhhao/hoshidicts/${revision}/`;
const source=await (await fetch(base+'src/deinflector.cpp')).text();
const conditions={NONE:0,V1D:1,V1P:2,V5D:4,V5SS:8,V5SP:16,VK:32,VS:64,VZ:128,ADJ_I:256,MASU:512,MASEN:1024,TE:2048,BA:4096,KU:8192,TA:16384,NN:32768,NASAI:65536,YA:131072,V1:3,V5S:24,V5:28,V:255};
function mask(s){return s.split('|').reduce((a,b)=>{b=b.trim();if(!(b in conditions))throw Error('Unknown condition '+b);return a|conditions[b]},0)}
function unquote(s){return JSON.parse('"'+s.replaceAll("\\'", "'")+'"')}
const groupToken=/add_group\(\{\s*\.name\s*=\s*"((?:\\.|[^"\\])*)"[\s\S]*?\}\);/g;
const ruleToken=/add_rule\(\s*\{\s*\.from\s*=\s*"((?:\\.|[^"\\])*)"\s*,\s*\.to\s*=\s*"((?:\\.|[^"\\])*)"\s*,\s*\.conditions_in\s*=\s*([^,]+),\s*\.conditions_out\s*=\s*([^,]+),\s*\.group_id\s*=\s*id\s*\}\);/g;
const irregularToken=/add_irregular\("((?:\\.|[^"\\])*)",\s*([^,]+),\s*([^,]+),\s*id\);/g;
const events=[...[...source.matchAll(groupToken)].map(m=>({i:m.index,type:'group',m})),...[...source.matchAll(ruleToken)].map(m=>({i:m.index,type:'rule',m})),...[...source.matchAll(irregularToken)].map(m=>({i:m.index,type:'irregular',m}))].sort((a,b)=>a.i-b.i);
const groups=[],rules=[];let group=-1;
for(const {type,m} of events){if(type==='group'){groups.push(unquote(m[1]));group++;continue}if(group<0)throw Error('Missing group');if(type==='rule'){rules.push([unquote(m[1]),unquote(m[2]),mask(m[3]),mask(m[4]),group]);continue}
 const suffix=unquote(m[1]),cin=mask(m[2]),cout=mask(m[3]);
 for(const [to,from] of [['いく','いっ'],['行く','行っ'],['逝く','逝っ'],['往く','往っ'],...['こう','とう','請う','乞う','恋う','問う','訪う','宣う','曰う','給う','賜う','揺蕩う'].map(v=>[v,v]),['のたまう','のたもう'],['たまう','たもう'],['たゆたう','たゆとう']])rules.push([from+suffix,to,cin,cout,group]);
}
const literals=(source.match(/\.group_id = id/g)||[]).length;
if(rules.length!==literals+4*19)throw Error(`Unparsed rules: literal ${literals}, emitted ${rules.length}`);
const destination=path.resolve('android/app/src/main/assets/dictionary');fs.mkdirSync(destination,{recursive:true});
fs.writeFileSync(path.join(destination,'hoshi-transforms.json'),JSON.stringify({revision,source:base+'src/deinflector.cpp',groups,rules}));
const kanjiSource=await (await fetch(base+'src/text_processor/kanji_variants.cpp')).text();
const kanji=[...kanjiSource.matchAll(/\{(0x[0-9a-f]+),\s*(0x[0-9a-f]+)\}/g)].map(m=>[Number(m[1]),Number(m[2])]);
if(kanji.length<1000)throw Error('Incomplete kanji variants');
fs.writeFileSync(path.join(destination,'hoshi-kanji-variants.json'),JSON.stringify(kanji));
fs.writeFileSync(path.join(destination,'HoshiDicts-LICENSE.txt'),await (await fetch(base+'LICENSE')).text());
console.log(`${groups.length} transform groups; ${rules.length} rules; ${kanji.length} kanji variants imported from ${revision}`);
