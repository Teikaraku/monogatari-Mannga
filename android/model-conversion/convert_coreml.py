"""Rebuild Aidoku's two published Core ML source graphs as portable ONNX.

No Apple inference API is required: protobuf graph and original FP16 weight bytes
are read directly. CPU inference uses FP32 operations with exactly the FP16-stored
weight values; intermediate FP16 rounding can differ from Apple's MLProgram.
Run with coremltools, numpy, onnx and onnxruntime installed.
"""
from pathlib import Path
import hashlib
import json
import struct
import numpy as np
import coremltools as ct
from coremltools.proto import MIL_pb2
import onnx
from onnx import helper as h, numpy_helper as nh, TensorProto as T
import onnxruntime as ort

ROOT = Path(__file__).resolve().parent
OUT = ROOT.parent / "app/src/main/assets/upscale"
OUT.mkdir(parents=True, exist_ok=True)

def save(nodes, initializers, source, name, shape, output_shape, extra):
    model = h.make_model(h.make_graph(nodes, name,
        [h.make_tensor_value_info("input", T.FLOAT, shape)],
        [h.make_tensor_value_info("output", T.FLOAT, output_shape)], initializers),
        opset_imports=[h.make_opsetid("", 17)], producer_name="Aidoku Core ML graph migration")
    model.ir_version = 8
    onnx.checker.check_model(model)
    dest = OUT / (name + ".onnx")
    onnx.save(model, dest)
    session = ort.InferenceSession(str(dest), providers=["CPUExecutionProvider"])
    inp = np.random.default_rng(7).random(shape, dtype=np.float32)
    result = session.run(None, {"input": inp})[0]
    assert list(result.shape) == output_shape and np.isfinite(result).all()
    extra.update(file=dest.name, inputShape=shape, outputShape=output_shape, scale=2,
        sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(), sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),
        originalSource="https://upscale.aidoku.app/models.json", intermediatePrecision="FP32 (original FP16 weights)",
        validation={"shape":list(result.shape), "min":float(result.min()), "max":float(result.max()), "finite":True})
    dest.with_suffix(".json").write_text(json.dumps(extra, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"model":name,"bytes":dest.stat().st_size,"nodes":len(nodes),"validation":extra["validation"]}))

def nn_weights(p):
    if p.floatValue: return np.asarray(p.floatValue, dtype=np.float32)
    if p.float16Value: return np.frombuffer(p.float16Value, dtype="<f2").astype(np.float32)
    raise ValueError("Unsupported quantized Core ML weight")

def convert_waifu():
    source = ROOT / "waifu2x_photo_noise0_scale2x.mlmodel"
    spec = ct.utils.load_spec(str(source)); nodes=[]; initializers=[]
    for layer in spec.neuralNetwork.layers:
        kind=layer.WhichOneof("layer"); ins=list(layer.input); outs=list(layer.output)
        if kind=="convolution":
            c=layer.convolution; group=c.nGroups or 1; shape=([c.kernelChannels,c.outputChannels//group] if c.isDeconvolution else [c.outputChannels,c.kernelChannels])+list(c.kernelSize)
            wn=layer.name+"_weights"; initializers.append(nh.from_array(nn_weights(c.weights).reshape(shape), wn));ins.append(wn)
            if c.hasBias:
                bn=layer.name+"_bias";initializers.append(nh.from_array(nn_weights(c.bias),bn));ins.append(bn)
            border=list(c.valid.paddingAmounts.borderAmounts)
            pads=[int(p.startEdgeSize) for p in border]+[int(p.endEdgeSize) for p in border]
            if len(pads)!=4:pads=[0]*4
            nodes.append(h.make_node("ConvTranspose" if c.isDeconvolution else "Conv",ins,outs,name=layer.name,
                kernel_shape=list(c.kernelSize),strides=list(c.stride),dilations=list(c.dilationFactor),pads=pads,group=group))
        elif kind=="activation":
            assert layer.activation.WhichOneof("NonlinearityType")=="leakyReLU"
            nodes.append(h.make_node("LeakyRelu",ins,outs,name=layer.name,alpha=layer.activation.leakyReLU.alpha))
        elif kind=="clip":
            for suffix,value in [("min",layer.clip.minVal),("max",layer.clip.maxVal)]:
                n=layer.name+suffix;initializers.append(nh.from_array(np.asarray(value,dtype=np.float32),n));ins.append(n)
            nodes.append(h.make_node("Clip",ins,outs,name=layer.name))
        else: raise ValueError(kind)
    save(nodes,initializers,source,"waifu2x_photo_noise0_scale2x",[1,3,156,156],[1,3,284,284],
        {"name":"waifu2x (photo, 2x, noise0)","blockSize":156,"shrinkSize":7,"tileOverlap":0})

def convert_realesrgan():
    source = next((ROOT/"RealESRGAN_x2plus.mlpackage").rglob("*.mlmodel"))
    spec=ct.utils.load_spec(str(source)); block=next(iter(spec.mlProgram.functions["main"].block_specializations.values()))
    constants={};types={};nodes=[];initializers=[];added=set()
    def const(name):
        value=constants[name];assert not isinstance(value,str),name
        if name not in added:initializers.append(nh.from_array(value,name));added.add(name)
        return name
    def extra(name,value):
        initializers.append(nh.from_array(np.asarray(value),name));added.add(name);return name
    for op in block.operations:
        for output in op.outputs:types[output.name]=[int(d.constant.size) for d in output.type.tensorType.dimensions]
        if op.type!="const":continue
        val=op.attributes["val"];typ=val.type.tensorType;shape=[int(d.constant.size) for d in typ.dimensions]
        dtype=MIL_pb2.DataType.Name(typ.dataType)
        if val.HasField("blobFileValue"):
            blob=val.blobFileValue;path=source.parent/blob.fileName.replace("@model_path/","")
            with path.open("rb") as file:
                file.seek(blob.offset);header=file.read(64);magic,_,length,offset=struct.unpack_from("<IIQQ",header)
                assert magic==0xDEADBEEF;file.seek(offset);raw=file.read(length)
            value=np.frombuffer(raw,dtype={"FLOAT16":"<f2","FLOAT32":"<f4"}[dtype])
        else:
            v=val.immediateValue.tensor
            if dtype=="STRING":constants[op.outputs[0].name]=v.strings.values[0];continue
            if v.HasField("bytes"):value=np.frombuffer(v.bytes.values,dtype={"FLOAT16":"<f2","FLOAT32":"<f4","UINT32":"<u4","INT32":"<i4","BOOL":"?"}[dtype])
            else:
                field={"FLOAT32":"floats","INT32":"ints","BOOL":"bools","UINT32":"ints"}[dtype]
                value=np.asarray(getattr(v,field).values)
        target=np.float32 if dtype.startswith("FLOAT") else np.bool_ if dtype=="BOOL" else np.int64
        constants[op.outputs[0].name]=value.astype(target).reshape(shape)
    input_shapes={"input":[1,3,256,256]}
    for op in block.operations:
        if op.type=="const":continue
        args={k:[a.name for a in v.arguments] for k,v in op.inputs.items()};outs=[o.name for o in op.outputs];name=outs[0]
        def one(key):return args[key][0]
        def value(key):return constants[one(key)]
        if op.type=="cast":nodes.append(h.make_node("Identity",[one("x")],outs,name=name))
        elif op.type=="conv":
            assert value("pad_type")=="custom"
            pads=value("pad").tolist();pads=[pads[0],pads[2],pads[1],pads[3]]
            nodes.append(h.make_node("Conv",[one("x"),const(one("weight")),const(one("bias"))],outs,name=name,
                strides=value("strides").tolist(),dilations=value("dilations").tolist(),pads=pads,group=int(value("groups"))))
        elif op.type=="leaky_relu":nodes.append(h.make_node("LeakyRelu",[one("x")],outs,name=name,alpha=float(value("alpha"))))
        elif op.type in ("add","mul"):
            ins=[const(one(k)) if one(k) in constants else one(k) for k in ("x","y")]
            nodes.append(h.make_node(op.type.title(),ins,outs,name=name))
        elif op.type=="concat":
            assert not bool(value("interleave"));nodes.append(h.make_node("Concat",args["values"],outs,name=name,axis=int(value("axis"))))
        elif op.type=="upsample_nearest_neighbor":
            scales=extra(name+"_scales",np.asarray([1,1,float(value("scale_factor_height")),float(value("scale_factor_width"))],dtype=np.float32))
            nodes.append(h.make_node("Resize",[one("x"),"",scales],outs,name=name,mode="nearest",coordinate_transformation_mode="asymmetric",nearest_mode="floor"))
        elif op.type=="pixel_unshuffle":
            r=int(value("downscale_factor"));n,c,height,width=types.get(one("x"),input_shapes.get(one("x")))
            shape1=extra(name+"_shape1",np.asarray([n,c,height//r,r,width//r,r],dtype=np.int64));shape2=extra(name+"_shape2",np.asarray([n,c*r*r,height//r,width//r],dtype=np.int64))
            nodes.append(h.make_node("Reshape",[one("x"),shape1],[name+"_reshape"],name=name+"_reshape"))
            nodes.append(h.make_node("Transpose",[name+"_reshape"],[name+"_transpose"],name=name+"_transpose",perm=[0,1,3,5,2,4]))
            nodes.append(h.make_node("Reshape",[name+"_transpose",shape2],outs,name=name))
        else:raise ValueError("Unsupported MIL operation: "+op.type)
    save(nodes,initializers,source,"RealESRGAN_x2plus",[1,3,256,256],[1,3,512,512],
        {"name":"Real-ESRGAN (2x, photo)","blockSize":256,"shrinkSize":0,"tileOverlap":0,"androidTileOverlap":16,
         "sourceWeightsSha256":hashlib.sha256(next(source.parent.rglob("weight.bin")).read_bytes()).hexdigest(),"ops":len(block.operations)})

if __name__=="__main__":
    convert_waifu();convert_realesrgan()
