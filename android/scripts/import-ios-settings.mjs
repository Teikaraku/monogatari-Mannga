import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const android=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),repo=path.resolve(android,'..');
async function files(root){const result=[];for(const item of await fs.readdir(root,{withFileTypes:true})){const p=path.join(root,item.name);if(item.isDirectory())result.push(...await files(p));else if(p.endsWith('.swift'))result.push(p)}return result}
function endOf(s,start,opening='(',closing=')'){let depth=0,quote=false,escaped=false;for(let i=start;i<s.length;i++){const c=s[i];if(quote){if(escaped)escaped=false;else if(c==='\\')escaped=true;else if(c==='"')quote=false;continue}if(c==='"'){quote=true;continue}if(c===opening)depth++;if(c===closing&&--depth===0)return i+1}throw Error('Unbalanced Swift block')}
const propertyKeys={},defaults={};
const scalar=raw=>{raw=raw.trim().replace(/, requires:[\s\S]*/,'');if(raw==='Date.distantPast')return -62135769600;
 if(raw.includes('SortMethod.lastOpened'))return 2;if(raw.includes('PinType.none'))return 'none';if(raw.includes('AppDelegate.isSideloaded'))return true;
 if(raw.includes('UIDevice.current.userInterfaceIdiom'))return Number(raw.split(':').at(-1));
 if(raw==='SourceLanguage.preferredCodes()')return ['en','multi'];if(raw==='[.safe, .containsNsfw]')return [0,1];
 if(raw.startsWith('.'))return ({'.singleTap':'single-tap','.longPress':'long-press'})[raw]||raw.slice(1);
 if(raw==='[]')return [];try{return JSON.parse(raw)}catch{return null}};
for(const file of await files(path.join(repo,'Monogatari/Core/Settings'))){
 const source=await fs.readFile(file,'utf8'),family=path.basename(file).replace('Settings.swift','').toLowerCase();
 for(const match of source.matchAll(/let\s+(\w+)\s*=\s*SettingsKey[^\n]*?\("([^"]+)"(?:,\s*default:\s*([^\n]+))?/g)){
   const key=match[2],prefix=family==='backups'?'AppSettings.backups.autoBackups':`AppSettings.${family}`;
   propertyKeys[`${prefix}.${match[1]}.key`]=key;
   if(match[3])defaults[key]=scalar(match[3].replace(/\)\s*$/,''));
 }
}
const app=await fs.readFile(path.join(repo,'Monogatari/App/AppDelegate.swift'),'utf8');
const region=app.slice(app.indexOf('defaults: ['),app.indexOf('AppSettings.registerDefaults()'));
for(const m of region.matchAll(/"([^"]+)":\s*("(?:\\.|[^"\\])*"|true|false|\d+)/g))defaults[m[1]]=scalar(m[2]);
propertyKeys['ReaderTextTheme.userDefaultsKey']='Reader.textTheme';propertyKeys['ReaderTextTheme.appearanceUserDefaultsKey']='Reader.textAppearance';
const source=await fs.readFile(path.join(repo,'Monogatari/Features/Settings/Settings.swift'),'utf8');
async function enumOptions(relative,name){
 const text=await fs.readFile(path.join(repo,relative),'utf8'),start=text.indexOf(`enum ${name}`);
 if(start<0)throw Error('Missing Swift enum '+name);
 const opening=text.indexOf('{',start),body=text.slice(opening,endOf(text,opening,'{','}'));
 const titles=new Map([...body.matchAll(/case\s+\.(`?\w+`?)\s*:\s*NSLocalizedString\("([^"]+)"\)/g)].map(m=>[m[1].replaceAll('`',''),m[2]]));
 const cases=[...body.matchAll(/^\s*case\s+(`?\w+`?)(?:\s*=\s*"([^"]+)")?\s*$/gm)];
 return cases.map(m=>{const key=m[1].replaceAll('`',''),title=titles.get(key);if(!title)throw Error('Missing title for '+name+'.'+key);return [m[2]||key,title]});
}
const enumDefinitions=[
 ['Library.pinTitles','Monogatari/Features/Library/LibraryViewModel.swift','PinType'],
 ['Reader.tapZones','Monogatari/Core/Settings/TapZone.swift','DefaultTapZones'],
 ['Reader.upscaleQuality','Monogatari/Core/Settings/Reader/UpscaleQuality.swift','UpscaleQuality'],
 ['Reader.autoScrollPosition','Monogatari/Core/Settings/Reader/AutoScrollPosition.swift','AutoScrollPosition'],
 ['Reader.textTheme','Monogatari/Features/Reader/Readers/Text/ReaderTextTheme.swift','ReaderTextTheme']
];
const enumValues=new Map(await Promise.all(enumDefinitions.map(async([key,file,name])=>[key,await enumOptions(file,name)])));
const calls=[];for(const match of source.matchAll(/\.init\s*\(/g)){const start=match.index,opening=source.indexOf('(',start);calls.push({start,end:endOf(source,opening)})}
function fields(block){const content=block.slice(block.indexOf('(')+1,-1),parts=[];let start=0,depth=0,quote=false,escape=false;for(let i=0;i<content.length;i++){
 const c=content[i];if(quote){if(escape)escape=false;else if(c==='\\')escape=true;else if(c==='"')quote=false;continue}if(c==='"'){quote=true;continue}
 if('([{'.includes(c))depth++;if(')]}'.includes(c))depth--;if(c===','&&depth===0){parts.push(content.slice(start,i));start=i+1}
}parts.push(content.slice(start));return Object.fromEntries(parts.map(p=>{const m=p.trim().match(/^(\w+):\s*([\s\S]*)$/);return m?[m[1],m[2]]:null}).filter(Boolean))}
const sections=[...source.matchAll(/(?:private\s+)?static\s+(?:let|var)\s+(\w+Settings)\s*:/g)].map(m=>({start:m.index,name:m[1].replace('Settings','')}));
function localizedTitle(raw=''){
 const keys=[...raw.matchAll(/NSLocalizedString\("([^"]+)"\)/g)].map(m=>m[1]);
 return {key:keys[0]==='%@_EXPERIMENTAL'?keys[1]||'':keys[0]||'',experimental:keys[0]==='%@_EXPERIMENTAL'};
}
function condition(raw=''){
 return propertyKeys[raw.trim()]||raw.match(/^"([\s\S]*)"$/)?.[1]||'';
}
const entries=[];
for(const match of source.matchAll(/\bkey:\s*("[^"\n]+"|AppSettings\.[\w.]+\.key|ReaderTextTheme\.\w+)/g)){
 const key=match[1].startsWith('"')?JSON.parse(match[1]):propertyKeys[match[1]];if(!key)throw Error('Unresolved setting '+match[1]);
 const call=calls.filter(c=>c.start<match.index&&c.end>match.index).sort((a,b)=>b.start-a.start)[0];if(!call)continue;
 const block=source.slice(call.start,call.end),type=block.match(/value:\s*\.(\w+)/)?.[1]||'custom';
 const titles=[...block.matchAll(/NSLocalizedString\("([^"]+)"\)/g)].map(m=>m[1]);
 const titleRaw=block.match(/title:\s*"([^"]+)"/)?.[1];
 const titleKey=titles[0]==='%@_EXPERIMENTAL'?titles[1]:titles[0]||titleRaw||key;
 let group=sections.filter(s=>s.start<call.start).at(-1)?.name||'root';
 const valuesRaw=block.match(/(?:values|options):\s*(\[[^\]]*\])/s)?.[1];let values=[];
 if(valuesRaw&&/^\[\s*(?:"|\d|true|false)/.test(valuesRaw))try{values=JSON.parse(valuesRaw.replace(/,\s*\]/g,']'))}catch{}
 const titleArray=block.match(/titles:\s*\[([\s\S]*?)\]/)?.[1];
 let optionTitles=titleArray?[...titleArray.matchAll(/NSLocalizedString\("([^"]+)"\)/g)].map(m=>m[1]):type==='segment'?titles.slice(1):[];
 if(enumValues.has(key)){const pairs=enumValues.get(key);values=pairs.map(p=>p[0]);optionTitles=pairs.map(p=>p[1]);}
 const number=(name)=>{const raw=block.match(new RegExp(name+':\\s*([0-9.]+)'))?.[1];return raw===undefined?null:Number(raw)};
 const requires=block.match(/requires:\s*"([^"]+)"/)?.[1]||propertyKeys[block.match(/requires:\s*(AppSettings\.[\w.]+\.key)/)?.[1]]||'';
 const requiresFalse=block.match(/requiresFalse:\s*"([^"]+)"/)?.[1]||propertyKeys[block.match(/requiresFalse:\s*(AppSettings\.[\w.]+\.key)/)?.[1]]||'';
 const ancestors=calls.filter(c=>c.start<call.start&&c.end>call.end).sort((a,b)=>a.start-b.start).map(c=>({call:c,fields:fields(source.slice(c.start,c.end))}));
 const parentPages=ancestors.map(c=>{
   return c.fields.value?.startsWith('.page')?c.fields.title?.match(/NSLocalizedString\("([^"]+)"\)/)?.[1]:null;
 }).filter(Boolean);
 const parentGroups=ancestors.filter(c=>c.fields.value?.startsWith('.group')),parentGroup=parentGroups.at(-1);
 const groupTitleMetadata=localizedTitle(parentGroup?.fields.title),groupId=parentGroup?.call.start||group,groupTitle=groupTitleMetadata.key;
 const groupValue=parentGroup?.fields.value||'',initStart=groupValue.indexOf('.init(');
 const groupFields=initStart<0?{}:fields(groupValue.slice(initStart,endOf(groupValue,groupValue.indexOf('(',initStart))));
 const groupFooter=localizedTitle(groupFields.footer).key;
 const groupRequires=parentGroups.map(c=>condition(c.fields.requires)).filter(Boolean).join(' && ');
 const groupRequiresFalse=parentGroups.map(c=>condition(c.fields.requiresFalse)).filter(Boolean);
 entries.push({key,titleKey,titleExperimental:titles[0]==='%@_EXPERIMENTAL',type,group,groupId,groupTitle,groupTitleExperimental:groupTitleMetadata.experimental,groupFooter,groupRequires,groupRequiresFalse,values,optionTitles,minimum:number('minimumValue'),maximum:number('maximumValue'),step:number('stepValue'),requires,requiresFalse,parentPages,
  source:'Monogatari/Features/Settings/Settings.swift',line:source.slice(0,call.start).split('\n').length});
}
// Features explicitly removed from the Android app stay out of regenerated settings/search.
const excludedAndroidKeys=new Set(['General.icloudSync','Flag.isiCloudAvailable','Advanced.migrateHistory','SourceLists']);
const androidDefaults=Object.fromEntries(Object.entries(defaults).filter(([key])=>!excludedAndroidKeys.has(key)));
const androidEntries=entries.filter(entry=>!excludedAndroidKeys.has(entry.key));
const out=path.join(android,'app/src/main/assets/ios');await fs.mkdir(out,{recursive:true});
await fs.writeFile(path.join(out,'defaults.json'),JSON.stringify(androidDefaults,null,2));await fs.writeFile(path.join(out,'settings-schema.json'),JSON.stringify(androidEntries,null,2));
console.log(JSON.stringify({defaults:Object.keys(androidDefaults).length,entries:androidEntries.length,groups:[...new Set(androidEntries.map(e=>e.group))],excludedAndroidKeys:[...excludedAndroidKeys]}));
