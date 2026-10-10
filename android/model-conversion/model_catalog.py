"""Fetch original Aidoku ModelInfo/version data and convert selected source models."""
from pathlib import Path
import argparse,hashlib,json,os,tempfile
from datetime import datetime,timezone
from urllib.parse import urljoin,urlsplit,urlunsplit,unquote
from urllib.request import Request,urlopen
from model_metadata import convert_with_metadata

DEFAULT_CATALOG='http://upscale.aidoku.app/models.json'
def fetch_bytes(address,limit):
    if urlsplit(address).scheme not in ('http','https'):raise ValueError('model URLs must use HTTP or HTTPS')
    with urlopen(Request(address,headers={'User-Agent':'Monogatari/0.9 (portable model converter)'}),timeout=60) as response:
        length=response.headers.get('Content-Length')
        if length and int(length)>limit:raise ValueError('remote model exceeds size limit')
        data=response.read(limit+1)
        if len(data)>limit:raise ValueError('remote model exceeds size limit')
        return data

def normalize_catalog(catalog):
    if not isinstance(catalog,dict) or not isinstance(catalog.get('models'),list):raise ValueError('catalog must contain a models array')
    result=[];seen=set()
    for item in catalog['models']:
        if not isinstance(item,dict) or str(item.get('type') or '').lower() not in ('image','multiarray'):continue
        if not isinstance(item.get('file'),str) or not item['file'] or item['file'] in seen:raise ValueError('invalid or duplicate catalog model file')
        seen.add(item['file']);row=dict(item);row['version']=int(row.get('version',1));result.append(row)
    return result

def fetch_catalog(address=DEFAULT_CATALOG):
    return normalize_catalog(json.loads(fetch_bytes(address,2*1024*1024).decode('utf-8-sig')))

def catalog_with_cache(address,cache,offline=False):
    """Keep a verified last directory; stale use is visible and URL-specific."""
    cache=Path(cache)
    try:
        if offline:raise OSError('cached catalog explicitly requested')
        models=fetch_catalog(address);document={'catalog':address,'models':models,'cachedAt':datetime.now(timezone.utc).isoformat()};cache.parent.mkdir(parents=True,exist_ok=True)
        with tempfile.NamedTemporaryFile('w',encoding='utf8',dir=cache.parent,delete=False) as output:json.dump(document,output,ensure_ascii=False);temporary=Path(output.name)
        try:os.replace(temporary,cache)
        finally:temporary.unlink(missing_ok=True)
        return models,False,None
    except Exception as error:
        try:
            if cache.stat().st_size>2*1024*1024:raise ValueError('catalog cache exceeds 2 MiB')
            document=json.loads(cache.read_text(encoding='utf8'));models=normalize_catalog(document)
            if document.get('catalog')!=address:raise ValueError('cached catalog URL differs from requested URL')
        except Exception:raise error
        return models,True,str(error)

def source_url(catalog,info):
    address=urljoin(catalog,info['file']);parts=urlsplit(address)
    if parts.path.endswith('.mlpackage'):address=urlunsplit(parts._replace(path=parts.path+'.zip'))
    if not urlsplit(address).path.endswith(('.mlmodel','.mlpackage.zip')):raise ValueError('catalog model must have readable Core ML source')
    return address

def installed_versions(output):
    result={}
    for path in Path(output).glob('*.json'):
        if path.name.endswith('.conversion.json'):continue
        try:
            info=json.loads(path.read_text(encoding='utf8'));key=info.get('catalogFile');version=int(info.get('version',0))
            if key:result[key]=max(result.get(key,0),version)
        except (ValueError,OSError):continue
    return result

def available_models(catalog,output):
    versions=installed_versions(output)
    return [item for item in catalog if item.get('version',1)>versions.get(Path(unquote(urlsplit(item['file']).path)).name,0)]

def convert_catalog_model(catalog,info,output,name=None,**overrides):
    output=Path(output);output.mkdir(parents=True,exist_ok=True);address=source_url(catalog,info);payload=fetch_bytes(address,1024**3);digest=hashlib.sha256(payload).hexdigest()
    expected=info.get('sourceSha256') or info.get('sha256')
    if expected and expected.lower()!=digest:raise ValueError('downloaded Core ML source SHA-256 does not match catalog')
    with tempfile.TemporaryDirectory(prefix='aidoku-catalog-') as temporary:
        source=Path(temporary)/Path(unquote(urlsplit(address).path)).name;source.write_bytes(payload);metadata={**info,'catalogFile':Path(unquote(urlsplit(info['file']).path)).name,'sourceFile':info['file'],'catalogUrl':catalog,'downloadUrl':address,'downloadSha256':digest};report=convert_with_metadata(source,output,name,metadata,**overrides);return report

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--catalog',default=DEFAULT_CATALOG);parser.add_argument('--catalog-cache',type=Path,default=Path(os.environ.get('LOCALAPPDATA',str(Path.home()/'.cache')))/'Monogatari/model-catalog.json');parser.add_argument('--offline',action='store_true',help='read the cached catalog explicitly; model source downloads still require network access');parser.add_argument('--list',action='store_true');parser.add_argument('--updates',action='store_true');parser.add_argument('--select',help='exact original catalog file, display name, or 1-based index');parser.add_argument('--all',action='store_true');parser.add_argument('--output',type=Path,default=Path.cwd()/'converted-models');parser.add_argument('--name');parser.add_argument('--block-size',type=int);parser.add_argument('--scale',type=int);parser.add_argument('--shrink-size',type=int);parser.add_argument('--allow-general',action='store_true');args=parser.parse_args()
    if hasattr(__import__('sys').stdout,'reconfigure'):__import__('sys').stdout.reconfigure(encoding='utf8')
    try:
        models,stale,cache_error=catalog_with_cache(args.catalog,args.catalog_cache,args.offline);models=available_models(models,args.output) if args.updates else models
        if args.list:print(json.dumps({'catalog':args.catalog,'models':models,'cached':stale,'cacheError':cache_error},ensure_ascii=False,indent=2));return
        if stale:print(f'Using cached catalog: {cache_error}',file=__import__('sys').stderr)
        if args.all:selected=models
        elif args.select:
            selected=[item for index,item in enumerate(models,1) if args.select in (item['file'],item.get('name'),str(index))]
            if len(selected)!=1:raise ValueError('select exactly one catalog model by file, display name or index')
        else:raise ValueError('use --list, --select or --all')
        if args.name and len(selected)>1:raise ValueError('--name requires a single selected model')
        results=[convert_catalog_model(args.catalog,item,args.output,args.name,shape=[1,3,args.block_size,args.block_size] if args.block_size else None,scale_override=args.scale,shrink_override=args.shrink_size,allow_general=args.allow_general) for item in selected]
        print(json.dumps([{key:value for key,value in result.items() if key not in ('graph','unsupported')} for result in results],ensure_ascii=False,indent=2))
    except Exception as error:parser.exit(2,f'Catalog operation failed: {error}\n')
if __name__=='__main__':main()
