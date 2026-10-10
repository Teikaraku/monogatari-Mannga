import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
const android = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const repo = path.resolve(android, '..');
const resourceRoot = path.join(repo, 'Monogatari/App/Resources');
const localizationRoot = path.join(resourceRoot, 'Localization');
const output = path.join(android, 'app/src/main/assets/ios');
await fs.mkdir(output,{recursive:true});
const localizations={},hashes={};
for(const dir of await fs.readdir(localizationRoot)) {
  if(!dir.endsWith('.lproj'))continue;
  const original=await fs.readFile(path.join(localizationRoot,dir,'Localizable.strings'),'utf8').catch(()=>null);
  if(original===null)continue;
  const map={};
  const string=raw=>JSON.parse('"'+raw.replace(/\\U([0-9a-fA-F]{4})/g,'\\u$1').replace(/\\'/g,"'")+'"');
  const regex=/"((?:\\.|[^"\\])*)"\s*=\s*"((?:\\.|[^"\\])*)"\s*;/g;
  for(const match of original.matchAll(regex))map[string(match[1])]=string(match[2]);
  localizations[dir.replace('.lproj','')]=map;
  hashes[dir]=crypto.createHash('sha256').update(original).digest('hex');
}
await fs.writeFile(path.join(output,'localizations.json'),JSON.stringify(localizations));
await fs.writeFile(path.join(output,'localization-hashes.json'),JSON.stringify(hashes,null,2));
const iconDirs=await fs.readdir(path.join(resourceRoot,'Assets.xcassets'));
await fs.mkdir(path.join(output,'images'),{recursive:true});
let images=0;
for(const dir of iconDirs.filter(n=>n.endsWith('.imageset'))) {
  const root=path.join(resourceRoot,'Assets.xcassets',dir);
  for(const file of await fs.readdir(root))if(/\.(png|jpg|jpeg|webp|svg)$/i.test(file)){
    await fs.copyFile(path.join(root,file),path.join(output,'images',`${dir.replace('.imageset','')}-${file}`));images++;
  }
}
console.log(JSON.stringify({languages:Object.keys(localizations).length,keys:Object.keys(localizations.en||{}).length,images}));
