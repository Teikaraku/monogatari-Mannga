"""Shared original ModelInfo contract for Android exports and desktop conversion."""
from pathlib import Path
import json,zipfile
from urllib.parse import urlsplit,unquote

MAX_METADATA=1024*1024
def parse_metadata(data):
    if len(data)>MAX_METADATA:raise ValueError('model metadata exceeds 1 MiB')
    value=json.loads(data.decode('utf-8-sig'));value=value.get('model',value)
    if not isinstance(value,dict):raise ValueError('model metadata must be a JSON object')
    if not isinstance(value.get('config',{}),dict):raise ValueError('model config must be an object')
    return value

def read_metadata(source,explicit=None):
    if explicit:return parse_metadata(Path(explicit).read_bytes())
    source=Path(source)
    if source.is_file() and zipfile.is_zipfile(source):
        with zipfile.ZipFile(source) as archive:
            entries=[entry for entry in archive.infolist() if entry.filename=='model.json']
            if len(entries)>1:raise ValueError('source bundle has duplicate model.json')
            if entries:
                if entries[0].file_size>MAX_METADATA:raise ValueError('model metadata exceeds 1 MiB')
                return parse_metadata(archive.read(entries[0]))
    for path in (Path(str(source)+'.json'),source.with_suffix('.json'),source.parent/'model.json'):
        if path.is_file():return parse_metadata(path.read_bytes())
    return {}

def convert_with_metadata(source,output,name=None,metadata=None,**overrides):
    from convert_model import convert
    source=Path(source);info=dict(read_metadata(source) if metadata is None else metadata);config=info.get('config',{});kind=str(info.get('type','')).lower();raw_file=info.get('sourceFile',info.get('file',''))
    if raw_file:info['sourceFile']=raw_file;info['catalogFile']=info.get('catalogFile') or Path(unquote(urlsplit(raw_file).path)).name
    if info:info['version']=int(info.get('version',1))
    values={key:value for key,value in overrides.items() if value is not None}
    if 'shape' not in values and kind!='image':
        if 'shape' in config:values['shape']=[int(x) for x in config['shape']]
        elif 'blockSize' in config:values['shape']=[1,3,int(config['blockSize']),int(config['blockSize'])]
    # Original ImageModel accepts config but never reads tile/feature options.
    defaults={'scale_override':None if kind=='image' else config.get('scale',2 if kind=='multiarray' else None),'shrink_override':None if kind=='image' else config.get('shrinkSize',0 if kind=='multiarray' else None),'tile_overlap':0 if kind=='image' else config.get('tileOverlap',0),'input_half_step':kind=='multiarray','preserve_grayscale':False if kind=='image' else config.get('preserveGrayscale',True)}
    for key,value in defaults.items():values.setdefault(key,value)
    label=name or info.get('name') or source.name.removesuffix('.zip').removesuffix('.mlpackage').removesuffix('.mlmodel')
    return convert(source,Path(output),label,metadata=info,**values)
