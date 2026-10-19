"""Create the tiny custom graphs used for Android model-import/device verification."""
from pathlib import Path
import json
from test_convert_model import nn_spec,mil_spec
from convert_model import convert

root=Path(__file__).resolve().parents[1]/'verification/custom-models'
root.mkdir(parents=True,exist_ok=True)
results=[]
for name,spec in [('custom-fp16-nn',nn_spec(fp16=True)),('custom-quant4-nn',nn_spec(quantized=True)),('custom-mil',mil_spec())]:
    source=root/(name+'.mlmodel');source.write_bytes(spec.SerializeToString());report=convert(source,root,name,scale_override=2,preserve_grayscale=False);results.append({key:report[key] for key in ['name','file','sha256','inputShape','outputShape','scale','shrinkSize','validation']})
(root/'verification.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps(results,indent=2))
