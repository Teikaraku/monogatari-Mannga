"""Compare generic conversion with the already audited bundled ONNX graphs."""
from pathlib import Path
import hashlib,json
import numpy as np
import onnxruntime as ort

root=Path(__file__).resolve().parents[1]
assets=root/'app/src/main/assets/upscale'
generic=root/'verification/model-converter'
results=[]
for original,converted,shape in [('waifu2x_photo_noise0_scale2x','audited-waifu',[1,3,156,156]),('RealESRGAN_x2plus','audited-esrgan',[1,3,256,256])]:
    source=assets/(original+'.onnx');destination=generic/(converted+'.onnx')
    original_metadata=json.loads((assets/(original+'.json')).read_text())
    digest=hashlib.sha256(source.read_bytes()).hexdigest()
    assert digest==original_metadata['sha256'],'original bundled asset changed'
    values=np.random.default_rng(7).random(shape,dtype=np.float32)
    sessions=[ort.InferenceSession(str(path),providers=['CPUExecutionProvider']) for path in (source,destination)]
    outputs=[session.run(None,{session.get_inputs()[0].name:values})[0] for session in sessions]
    difference=np.abs(outputs[0]-outputs[1]);result={'model':original,'originalSha256':digest,'genericSha256':hashlib.sha256(destination.read_bytes()).hexdigest(),'inputShape':shape,'outputShape':list(outputs[0].shape),'maxAbsDifference':float(difference.max()),'meanAbsDifference':float(difference.mean()),'exactEqual':bool(np.array_equal(*outputs))}
    assert result['maxAbsDifference']<=1e-6,result
    results.append(result)
(root/'verification/model-converter/official-graph-comparison.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
print(json.dumps(results,indent=2))
