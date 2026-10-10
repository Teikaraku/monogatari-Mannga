"""Additional whole-image and scale>4 fixtures; existing device fixtures stay intact."""
from pathlib import Path
import json
from coremltools.proto import Model_pb2
from convert_model import convert
from test_convert_model import nn_spec

root=Path(__file__).resolve().parents[1]/'verification/model-converter/image-fixtures'
root.mkdir(parents=True,exist_ok=True)
results=[]
for name,gray in [('custom-gray-image',True),('custom-rect-image',False)]:
    spec=Model_pb2.Model();spec.specificationVersion=6
    for output,label,width,height in [(False,'image',7,5),(True,'rendered',42 if gray else 10,15 if gray else 8)]:
        f=(spec.description.output if output else spec.description.input).add();f.name=label;f.type.imageType.colorSpace=10 if gray else 20;f.type.imageType.width=width;f.type.imageType.height=height
    network=spec.neuralNetwork;network.imageInputShapeMapping=1;l=network.layers.add();l.name='resize';l.input.append('image');l.output.append('rendered')
    if gray:l.upsample.scalingFactor.extend([3,6])
    else:l.resizeBilinear.targetSize.extend([8,10]);l.resizeBilinear.mode.samplingMethod=2
    source=root/(name+'.mlmodel');source.write_bytes(spec.SerializeToString());results.append(convert(source,root,name))
spec=nn_spec();spec.description.input[0].type.multiArrayType.shape[:]=[1,3,3,3];spec.description.output[0].type.multiArrayType.shape[:]=[1,3,15,15];spec.neuralNetwork.layers[-1].upsample.scalingFactor[:]=[5,5]
source=root/'custom-scale5.mlmodel';source.write_bytes(spec.SerializeToString());results.append(convert(source,root,'custom-scale5',scale_override=5))
(root/'verification.json').write_text(json.dumps([{key:value for key,value in result.items() if key not in ('graph','unsupported')} for result in results],indent=2),encoding='utf-8')
print(json.dumps([{key:result[key] for key in ('file','sha256','processingMode','inputShape','outputShape','inputName','outputName','scale')} for result in results],indent=2))
