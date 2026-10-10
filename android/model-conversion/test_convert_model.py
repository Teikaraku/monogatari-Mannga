"""Independent tiny NN/MIL graphs and NumPy references, requiring no Apple runtime."""
import json, math, tempfile, unittest, zipfile
from pathlib import Path
import numpy as np
import onnx, onnxruntime as ort
from convert_model import convert, unpack_bits, nn_weights, mil_value, Graph, lower_mil, lower_nn, lower_nn_recurrent, decompress_lut, decompress_shift_scale, Unsupported
from coremltools.proto import Model_pb2, MIL_pb2, NeuralNetwork_pb2

def feature(spec,name,shape,output=False):
    f=(spec.description.output if output else spec.description.input).add();f.name=name;f.type.multiArrayType.dataType=65568;f.type.multiArrayType.shape.extend(shape)
def tensor(value,dtype,shape):
    value.tensorType.dataType=dtype;value.tensorType.rank=len(shape)
    for size in shape:value.tensorType.dimensions.add().constant.size=size
def nn_spec(quantized=False,fp16=False,image=False):
    spec=Model_pb2.Model();spec.specificationVersion=4;feature(spec,'input',[1,3,16,16]);feature(spec,'output',[1,3,32,32],True)
    if image:
        for f in [spec.description.input[0],spec.description.output[0]]:f.type.ClearField('multiArrayType');f.type.imageType.width=16 if f.name=='input' else 32;f.type.imageType.height=f.type.imageType.width;f.type.imageType.colorSpace=30
        p=spec.neuralNetwork.preprocessing.add();p.featureName='input';p.scaler.channelScale=1/255
    layer=spec.neuralNetwork.layers.add();layer.name='channel_identity';layer.input.append('input');layer.output.append('convolved');c=layer.convolution;c.outputChannels=3;c.kernelChannels=3;c.nGroups=1;c.kernelSize.extend([1,1]);c.stride.extend([1,1]);c.dilationFactor.extend([1,1]);c.valid.SetInParent();weights=np.eye(3,dtype=np.float32).reshape(3,3,1,1)
    if quantized:
        bits=np.array([(int(v)>>bit)&1 for v in weights.flatten() for bit in range(3,-1,-1)],dtype=np.uint8);c.weights.rawValue=np.packbits(bits,bitorder='big').tobytes();c.weights.quantization.numberOfBits=4;c.weights.quantization.linearQuantization.scale.extend([1]*3);c.weights.quantization.linearQuantization.bias.extend([0]*3)
    elif fp16:c.weights.float16Value=weights.astype('<f2').tobytes()
    else:c.weights.floatValue.extend(weights.flatten())
    layer=spec.neuralNetwork.layers.add();layer.name='clamp';layer.input.append('convolved');layer.output.append('clamped');layer.activation.ReLU.SetInParent()
    layer=spec.neuralNetwork.layers.add();layer.name='nearest2x';layer.input.append('clamped');layer.output.append('output');layer.upsample.scalingFactor.extend([2,2]);layer.upsample.mode=0
    return spec
def mil_spec():
    spec=Model_pb2.Model();spec.specificationVersion=6;feature(spec,'input',[1,3,16,16]);feature(spec,'output',[1,3,32,32],True);spec.mlProgram.version=1;function=spec.mlProgram.functions['main'];inp=function.inputs.add();inp.name='input';tensor(inp.type,MIL_pb2.FLOAT32,[1,3,16,16]);block=function.block_specializations['CoreML5'];block.outputs.append('output')
    def const(name,value):
        op=block.operations.add();op.type='const';output=op.outputs.add();output.name=name;tensor(output.type,MIL_pb2.FLOAT32,[]);v=op.attributes['val'];tensor(v.type,MIL_pb2.FLOAT32,[]);v.immediateValue.tensor.floats.values.append(value)
    def op(kind,name,bindings,shape):
        operation=block.operations.add();operation.type=kind
        for key,value in bindings.items():operation.inputs[key].arguments.add().name=value
        output=operation.outputs.add();output.name=name;tensor(output.type,MIL_pb2.FLOAT32,shape);return operation
    const('half',0.5);const('factor',2);op('mul','scaled',{'x':'input','y':'half'},[1,3,16,16]);op('relu','clamped',{'x':'scaled'},[1,3,16,16]);op('upsample_nearest_neighbor','output',{'x':'clamped','scale_factor_height':'factor','scale_factor_width':'factor'},[1,3,32,32]);return spec

def mil_const(block,name,value,dtype=MIL_pb2.FLOAT32):
    op=block.operations.add();op.type='const';out=op.outputs.add();out.name=name;shape=list(np.shape(value));tensor(out.type,dtype,shape);v=op.attributes['val'];tensor(v.type,dtype,shape);field={MIL_pb2.FLOAT32:'floats',MIL_pb2.FLOAT64:'doubles',MIL_pb2.INT64:'longInts',MIL_pb2.BOOL:'bools',MIL_pb2.STRING:'strings'}.get(dtype,'ints');getattr(v.immediateValue.tensor,field).values.extend(np.asarray(value).flatten().tolist())

def mil_op(block,kind,name,args,shape,dtype=MIL_pb2.FLOAT32):
    op=block.operations.add();op.type=kind
    for key,value in args.items():
        for item in value if isinstance(value,list) else [value]:op.inputs[key].arguments.add().name=item
    out=op.outputs.add();out.name=name;tensor(out.type,dtype,shape);return op

def execute_graph(graph,outputs,inputs,output_shapes):
    from onnx import helper as h,TensorProto as t
    model=h.make_model(h.make_graph(graph.nodes,'reference',[h.make_tensor_value_info(name,t.FLOAT,list(value.shape)) for name,value in inputs.items()],[h.make_tensor_value_info(name,t.FLOAT,shape) for name,shape in zip(outputs,output_shapes)],list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10
    return ort.InferenceSession(model.SerializeToString()).run(None,inputs)

class ConverterTests(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def write(self,spec,name='model.mlmodel'):p=self.root/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(spec.SerializeToString());return p
    def run_model(self,path,data):
        session=ort.InferenceSession(str(path),providers=['CPUExecutionProvider']);return session.run(None,{session.get_inputs()[0].name:data})[0]
    def test_fp16_nn_matches_numpy_pixels(self):
        report=convert(self.write(nn_spec(fp16=True)),self.root/'out','fp16');data=np.random.default_rng(1).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/fp16.onnx',data),np.repeat(np.repeat(data,2,2),2,3));self.assertEqual(report['scale'],2);self.assertEqual(report['shrinkSize'],0)
    def test_quantized_nn_matches_numpy_pixels(self):
        report=convert(self.write(nn_spec(quantized=True)),self.root/'out','quant4');data=np.random.default_rng(2).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/quant4.onnx',data),np.repeat(np.repeat(data,2,2),2,3));self.assertTrue(report['appCompatible'])
    def test_independent_mil_matches_numpy_pixels_and_metadata(self):
        report=convert(self.write(mil_spec()),self.root/'out','mil2x',scale_override=2,tile_overlap=2);data=np.random.default_rng(3).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/mil2x.onnx',data),np.repeat(np.repeat(data*0.5,2,2),2,3));meta={p.key:p.value for p in onnx.load(self.root/'out/mil2x.onnx').metadata_props};self.assertEqual(meta['aidoku.scale'],'2');self.assertEqual(meta['aidoku.inputHalfStep'],'false');self.assertEqual(meta['aidoku.tileOverlap'],'2')
    def test_package_zip_uses_same_readable_mil(self):
        source=self.write(mil_spec(),'Custom.mlpackage/Data/com.apple.CoreML/model.mlmodel');archive=self.root/'Custom.mlpackage.zip'
        with zipfile.ZipFile(archive,'w') as zip:zip.write(source,'Custom.mlpackage/Data/com.apple.CoreML/model.mlmodel')
        report=convert(archive,self.root/'out','zip');self.assertEqual(report['outputShape'],[1,3,32,32]);self.assertTrue((self.root/'out/zip.aidoku-model.zip').exists())
    def test_all_unknown_nodes_are_reported_with_shapes(self):
        spec=nn_spec()
        for name in ['unsupported-first','unsupported-second']:
            layer=spec.neuralNetwork.layers.add();layer.name=name;layer.input.append('output');layer.output.append(name);layer.custom.className='UserCustomOperator'
        with self.assertRaises(Unsupported):convert(self.write(spec),self.root/'out','unknown')
        report=json.loads((self.root/'out/unknown.conversion.json').read_text());self.assertEqual([x['name'] for x in report['unsupported']],['unsupported-first','unsupported-second']);self.assertIn('inputShapes',report['unsupported'][0]);self.assertFalse((self.root/'out/unknown.onnx').exists())
    def test_archive_paths_cannot_escape_staging(self):
        source=self.root/'unsafe.zip'
        with zipfile.ZipFile(source,'w') as zip:zip.writestr('../escaped.mlmodel',b'not a model')
        with self.assertRaises(Unsupported):convert(source,self.root/'out','unsafe')
        self.assertFalse((self.root/'escaped.mlmodel').exists())
    def test_failed_validation_preserves_previous_working_model(self):
        source=self.write(nn_spec());out=self.root/'out';convert(source,out,'preserved');previous=(out/'preserved.onnx').read_bytes();package=(out/'preserved.aidoku-model.zip').read_bytes()
        with self.assertRaises(Unsupported):convert(source,out,'preserved',scale_override=4,shrink_override=0)
        self.assertEqual((out/'preserved.onnx').read_bytes(),previous);self.assertEqual((out/'preserved.aidoku-model.zip').read_bytes(),package);self.assertFalse(list(out.glob('.preserved-*.onnx')))
        report=json.loads((out/'preserved.conversion.json').read_text());self.assertEqual(report['unsupported'][0]['operator'],'model CPU/shape validation');self.assertIn('integer scale',report['unsupported'][0]['reason'])
    def test_distinct_nn_and_mil_packed_bit_order(self):
        np.testing.assert_array_equal(unpack_bits(bytes([0x12,0x34]),4,4),[1,2,3,4]);np.testing.assert_array_equal(unpack_bits(bytes([0x21,0x43]),4,4,bitorder='little'),[1,2,3,4]);np.testing.assert_array_equal(unpack_bits(bytes([0xA1]),4,2,True,bitorder='little'),[1,-6])
    def test_image_bgr_preprocessing_has_canonical_rgb_boundary(self):
        spec=nn_spec(image=True);report=convert(self.write(spec),self.root/'out','image');session=ort.InferenceSession(str(self.root/'out/image.onnx'));data=np.random.default_rng(4).random((1,3,16,16),dtype=np.float32);actual=session.run(None,{session.get_inputs()[0].name:data})[0];np.testing.assert_allclose(actual,np.repeat(np.repeat(data/255,2,2),2,3),rtol=1e-6,atol=1e-8);self.assertTrue(report['appCompatible'])
    def test_explicit_block_size_shape_reaches_mil_resize(self):
        report=convert(self.write(mil_spec()),self.root/'out','shape',[1,3,32,32]);self.assertEqual(report['inputShape'],[1,3,32,32]);self.assertEqual(report['outputShape'],[1,3,64,64])
    def test_gelu_modes_match_independent_numpy_reference(self):
        data=np.linspace(-3,3,1*3*16*16,dtype=np.float32).reshape(1,3,16,16)
        for mode in range(3):
            spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation');layer.gelu.mode=mode;convert(self.write(spec),self.root/'out',f'gelu{mode}')
            actual=self.run_model(self.root/f'out/gelu{mode}.onnx',data)
            if mode==0:reference=0.5*data*(1+np.vectorize(math.erf)(data/math.sqrt(2)))
            elif mode==1:reference=0.5*data*(1+np.tanh(math.sqrt(2/math.pi)*(data+0.044715*data**3)))
            else:reference=data/(1+np.exp(-1.702*data))
            np.testing.assert_allclose(actual,np.repeat(np.repeat(reference,2,2),2,3),rtol=2e-4,atol=2e-6)
    def test_threshold_activation_preserves_equal_boundary(self):
        spec=nn_spec();activation=spec.neuralNetwork.layers[1].activation;activation.ClearField('ReLU');activation.thresholdedReLU.alpha=0.5;convert(self.write(spec),self.root/'out','threshold');data=np.full((1,3,16,16),0.5,dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/threshold.onnx',data),np.full((1,3,32,32),0.5,dtype=np.float32))
    def test_nn_comparison_returns_float_tensor(self):
        spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation');layer.greaterEqual.alpha=0.5;convert(self.write(spec),self.root/'out','compare');data=np.linspace(0,1,1*3*16*16,dtype=np.float32).reshape(1,3,16,16);np.testing.assert_array_equal(self.run_model(self.root/'out/compare.onnx',data),np.repeat(np.repeat((data>=0.5).astype(np.float32),2,2),2,3))
    def test_nn_offset_resize_matches_coreml_sampling_formula(self):
        spec=nn_spec();layer=spec.neuralNetwork.layers[2];layer.ClearField('upsample');layer.resizeBilinear.targetSize.extend([32,32]);layer.resizeBilinear.mode.samplingMethod=3;convert(self.write(spec),self.root/'out','offset');yy,xx=np.indices((16,16));data=np.broadcast_to((yy+xx).astype(np.float32)/32,(1,3,16,16)).copy();actual=self.run_model(self.root/'out/offset.onnx',data);positions=(np.arange(32)+0.5)*15/32;expected=np.broadcast_to(((positions[:,None]+positions[None,:])/32).astype(np.float32),(1,3,32,32));np.testing.assert_allclose(actual,expected,rtol=0,atol=1e-7)
    def test_mean_image_subtraction_retains_bgr_channel_order(self):
        spec=nn_spec(image=True);del spec.neuralNetwork.preprocessing[:];mean=np.broadcast_to(np.array([5,10,15],dtype=np.float32).reshape(3,1,1),(3,16,16)).copy();p=spec.neuralNetwork.preprocessing.add();p.featureName='input';p.meanImage.meanImage.extend(mean.flatten());convert(self.write(spec),self.root/'out','mean');session=ort.InferenceSession(str(self.root/'out/mean.onnx'));data=np.random.default_rng(17).random((1,3,16,16),dtype=np.float32);actual=session.run(None,{session.get_inputs()[0].name:data})[0];expected=np.maximum(data-np.array([15,10,5],dtype=np.float32).reshape(1,3,1,1)/255,0);np.testing.assert_allclose(actual,np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-5,atol=1e-7)
    def test_tensor_pipeline_scopes_reused_layer_names(self):
        first=nn_spec();second=nn_spec();second.description.input[0].name='output';second.description.input[0].type.multiArrayType.shape[:]=[1,3,32,32];second.description.output[0].name='final';second.description.output[0].type.multiArrayType.shape[:]=[1,3,64,64]
        second.neuralNetwork.layers[0].input[0]='output';second.neuralNetwork.layers[-1].output[0]='final'
        spec=Model_pb2.Model();spec.specificationVersion=4;feature(spec,'input',[1,3,16,16]);feature(spec,'final',[1,3,64,64],True);spec.pipeline.models.add().CopyFrom(first);spec.pipeline.models.add().CopyFrom(second);report=convert(self.write(spec),self.root/'out','pipeline');data=np.random.default_rng(18).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/pipeline.onnx',data),np.repeat(np.repeat(data,4,2),4,3));self.assertEqual(report['scale'],4)
    def test_mil_integer_floor_division_matches_signed_numpy_without_float_rounding(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];block.outputs[:]=['output']
        def const(name,value,dtype,field):
            op=block.operations.add();op.type='const';out=op.outputs.add();out.name=name;tensor(out.type,dtype,[]);v=op.attributes['val'];tensor(v.type,dtype,[]);getattr(v.immediateValue.tensor,field).values.append(value)
        def op(kind,name,args,dtype):
            o=block.operations.add();o.type=kind
            for key,value in args.items():o.inputs[key].arguments.add().name=value
            result=o.outputs.add();result.name=name;tensor(result.type,dtype,[1,3,16,16])
        const('i32','int32',MIL_pb2.STRING,'strings');const('fp32','fp32',MIL_pb2.STRING,'strings');const('divisor',-3,MIL_pb2.INT32,'ints');op('cast','integers',{'x':'input','dtype':'i32'},MIL_pb2.INT32);op('floor_div','quotient',{'x':'integers','y':'divisor'},MIL_pb2.INT32);op('cast','output',{'x':'quotient','dtype':'fp32'},MIL_pb2.FLOAT32)
        convert(self.write(spec),self.root/'out','floordiv');data=np.resize(np.array([-2147483520,-17,-1,0,1,17,2147483520],dtype=np.float32),(1,3,16,16));np.testing.assert_array_equal(self.run_model(self.root/'out/floordiv.onnx',data),np.floor_divide(data.astype(np.int32),np.int32(-3)).astype(np.float32))
    def test_inline_mil_packed_signed_weights_match_blob_bit_semantics(self):
        value=MIL_pb2.Value();tensor(value.type,MIL_pb2.INT4,[4]);value.immediateValue.tensor.bytes.values=bytes([0xA1,0xF7]);np.testing.assert_array_equal(mil_value(value,self.root/'model.mlmodel'),[1,-6,7,-1])
    def test_nn_public_normalization_branches_match_numpy(self):
        data=np.random.default_rng(19).normal(size=(1,3,16,16)).astype(np.float32)
        for kind in ('mvn','l2normalize','batchnorm'):
            for across in (False,True):
                with self.subTest(operator=kind,across=across):
                    spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation');p=getattr(layer,kind);p.epsilon=1e-5
                    if kind=='mvn':p.acrossChannels=across;p.normalizeVariance=True;axes=(1,2,3) if across else (2,3);center=data-data.mean(axis=axes,keepdims=True);expected=center/np.sqrt((center**2).mean(axis=axes,keepdims=True)+p.epsilon)
                    elif kind=='l2normalize':expected=data/np.sqrt((data**2).sum(axis=(1,2,3),keepdims=True)+p.epsilon)
                    else:
                        p.channels=3;p.computeMeanVar=True;p.instanceNormalization=not across;p.gamma.floatValue.extend([1,2,3]);p.beta.floatValue.extend([0.1,0.2,0.3]);axes=(0,2,3) if across else (2,3);center=data-data.mean(axis=axes,keepdims=True);expected=center/np.sqrt((center**2).mean(axis=axes,keepdims=True)+p.epsilon)*np.array([1,2,3],dtype=np.float32).reshape(1,3,1,1)+np.array([0.1,0.2,0.3],dtype=np.float32).reshape(1,3,1,1)
                    convert(self.write(spec),self.root/'out','norm');np.testing.assert_allclose(self.run_model(self.root/'out/norm.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=2e-5,atol=2e-6)
    def test_nn_valid_crop_preserves_halo_dimensions(self):
        spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation')
        for _ in range(2):edge=layer.crop.cropAmounts.borderAmounts.add();edge.startEdgeSize=1;edge.endEdgeSize=1
        report=convert(self.write(spec),self.root/'out','crop',scale_override=2);self.assertEqual(report['shrinkSize'],1);data=np.random.default_rng(20).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/crop.onnx',data),np.repeat(np.repeat(data[:,:,1:-1,1:-1],2,2),2,3))
    def test_nn_l2_pool_matches_sum_of_squares_including_last_pixel(self):
        data=np.random.default_rng(21).random((1,3,16,16),dtype=np.float32)
        for include in (False,True):
            with self.subTest(includeLastPixel=include):
                spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation');p=layer.pooling;p.type=2;p.kernelSize.extend([3,3] if include else [2,2]);p.stride.extend([2,2])
                if include:p.includeLastPixel.paddingAmounts.extend([1,1]);padded=np.pad(data,[(0,0),(0,0),(1,3),(1,3)]);expected=np.empty((1,3,9,9),dtype=np.float32)
                else:p.valid.SetInParent();padded=data;expected=np.empty((1,3,8,8),dtype=np.float32)
                kernel=3 if include else 2
                for h in range(expected.shape[2]):
                    for w in range(expected.shape[3]):expected[:,:,h,w]=np.sqrt((padded[:,:,h*2:h*2+kernel,w*2:w*2+kernel]**2).sum(axis=(2,3)))
                convert(self.write(spec),self.root/'out','lp',allow_general=include);np.testing.assert_allclose(self.run_model(self.root/'out/lp.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=2e-6,atol=2e-7)
    def test_legacy_channel_last_flatten_retains_rank(self):
        spec=nn_spec();layer=spec.neuralNetwork.layers[1];layer.ClearField('activation');layer.flatten.mode=1;spec.neuralNetwork.layers[-1].input[0]='restored';reshape=spec.neuralNetwork.layers.add();reshape.name='restore_chw';reshape.input.append('clamped');reshape.output.append('restored');reshape.reshape.targetShape.extend([3,16,16]);saved=NeuralNetwork_pb2.NeuralNetworkLayer();saved.CopyFrom(spec.neuralNetwork.layers[-2]);spec.neuralNetwork.layers[-2].CopyFrom(spec.neuralNetwork.layers[-1]);spec.neuralNetwork.layers[-1].CopyFrom(saved)
        convert(self.write(spec),self.root/'out','flat');data=np.random.default_rng(22).random((1,3,16,16),dtype=np.float32);expected=data.transpose(0,2,3,1).reshape(1,3,16,16);np.testing.assert_array_equal(self.run_model(self.root/'out/flat.onnx',data),np.repeat(np.repeat(expected,2,2),2,3))
    def test_mil_conditional_selects_both_runtime_branches(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'half',0.5);mil_const(block,'two',2.0);mil_const(block,'axes',[0,1,2,3],MIL_pb2.INT32);mil_op(block,'reduce_mean','average',{'x':'input','axes':'axes'},[]);mil_op(block,'greater','condition',{'x':'average','y':'half'},[],MIL_pb2.BOOL);condition=mil_op(block,'cond','selected',{'pred':'condition'},[1,3,16,16])
        for factor in ('two','half'):
            branch=condition.blocks.add();branch.outputs.append('result');mil_op(branch,'mul','result',{'x':'input','y':factor},[1,3,16,16])
        mil_op(block,'upsample_nearest_neighbor','output',{'x':'selected','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','conditional')
        for level,factor in ((0.25,0.5),(0.75,2)):
            data=np.full((1,3,16,16),level,dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/conditional.onnx',data),np.repeat(np.repeat(data*factor,2,2),2,3))
    def test_mil_instance_norm_and_interleaved_concat_match_numpy(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'half',0.5);mil_const(block,'two',2.0);mil_const(block,'axis',1,MIL_pb2.INT32);mil_const(block,'indices',[1,3,5],MIL_pb2.INT32);mil_const(block,'yes',True,MIL_pb2.BOOL);mil_op(block,'instance_norm','normalized',{'x':'input'},[1,3,16,16]);mil_op(block,'mul','scaled',{'x':'normalized','y':'half'},[1,3,16,16]);mil_op(block,'concat','combined',{'values':['normalized','scaled'],'axis':'axis','interleave':'yes'},[1,6,16,16]);mil_op(block,'gather','selected',{'x':'combined','indices':'indices','axis':'axis'},[1,3,16,16]);mil_op(block,'upsample_nearest_neighbor','output',{'x':'selected','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','interleave');data=np.random.default_rng(23).normal(size=(1,3,16,16)).astype(np.float32);center=data-data.mean(axis=(2,3),keepdims=True);expected=center/np.sqrt((center**2).mean(axis=(2,3),keepdims=True)+1e-5)*0.5;np.testing.assert_allclose(self.run_model(self.root/'out/interleave.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-5,atol=1e-6)
    def test_resampling_padding_and_coordinate_modes_match_independent_pixels(self):
        from onnx import helper as h,TensorProto as t
        data=np.arange(6,dtype=np.float32).reshape(1,1,2,3)
        pixels=np.array([[-3.2,-1.4],[-0.2,0.3],[0.6,1.2],[2.4,0.8],[4.7,3.1]],dtype=np.float32).reshape(1,1,5,2)
        def coordinate(v,size,padding):
            if padding=='border':return np.clip(v,0,size-1)
            if padding=='reflection':r=v%(2*max(size-1,1));return np.clip(max(size-1,1)-abs(r-max(size-1,1)),0,size-1)
            if padding=='symmetric':r=(v+0.5)%(2*size);return np.clip(size-0.5-abs(r-size),0,size-1)
            return v
        for padding in ('constant','border','reflection','symmetric'):
            for mode in ('unnormalized','normalized_zero_to_one','normalized_minus_one_to_one'):
                for align in (False,True):
                    with self.subTest(padding=padding,mode=mode,align=align):
                        coords=pixels.copy()
                        if mode!='unnormalized':
                            coords=(coords if align else coords+0.5)/np.array([2,1] if align else [3,2],dtype=np.float32)
                            if mode=='normalized_minus_one_to_one':coords=coords*2-1
                        graph=Graph();graph.shapes['input']=list(data.shape);graph.resample('input',graph.const(coords),['output'],padding=padding,padding_value=7,coordinates_mode=mode,align_corners=align)
                        model=h.make_model(h.make_graph(graph.nodes,'resample',[h.make_tensor_value_info('input',t.FLOAT,list(data.shape))],[h.make_tensor_value_info('output',t.FLOAT,[1,1,1,5])],list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10
                        actual=ort.InferenceSession(model.SerializeToString()).run(None,{'input':data})[0];expected=[]
                        for x,y in pixels.reshape(-1,2):
                            x,y=coordinate(float(x),3,padding),coordinate(float(y),2,padding);ix,iy=math.floor(x),math.floor(y);wx,wy=x-ix,y-iy;value=0
                            for dx,dy,weight in ((0,0,(1-wx)*(1-wy)),(1,0,wx*(1-wy)),(0,1,(1-wx)*wy),(1,1,wx*wy)):
                                xx,yy=ix+dx,iy+dy;sample=data[0,0,np.clip(yy,0,1),np.clip(xx,0,2)] if padding!='constant' else data[0,0,yy,xx] if 0<=yy<2 and 0<=xx<3 else 7;value+=sample*weight
                            expected.append(value)
                        np.testing.assert_allclose(actual.flatten(),expected,rtol=1e-5,atol=2e-6)
    def test_mil_affine_identity_and_translation_match_linear_image(self):
        for offset in (0,2/15):
            spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'matrix',[[1,0,offset,0,1,0]]);mil_const(block,'height',16,MIL_pb2.INT32);mil_const(block,'width',16,MIL_pb2.INT32);mil_const(block,'two',2.0);mil_op(block,'affine','transformed',{'x':'input','transform_matrix':'matrix','output_height':'height','output_width':'width'},[1,3,16,16]);mil_op(block,'upsample_nearest_neighbor','output',{'x':'transformed','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','affine');data=np.random.default_rng(24).random((1,3,16,16),dtype=np.float32);expected=data if offset==0 else np.pad(data[:,:,:,1:],[(0,0),(0,0),(0,0),(0,1)]);np.testing.assert_allclose(self.run_model(self.root/'out/affine.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-5,atol=2e-6)
    def test_mil_space_batch_transform_preserves_order_and_crop(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'blocks',[2,3],MIL_pb2.INT32);mil_const(block,'padding',[[1,1],[1,1]],MIL_pb2.INT32);mil_const(block,'batch_bias',np.arange(6,dtype=np.float32).reshape(6,1,1,1));mil_const(block,'two',2.0);mil_op(block,'space_to_batch','batches',{'x':'input','block_shape':'blocks','paddings':'padding'},[6,3,9,6]);mil_op(block,'add','adjusted',{'x':'batches','y':'batch_bias'},[6,3,9,6]);mil_op(block,'batch_to_space','restored',{'x':'adjusted','block_shape':'blocks','crops':'padding'},[1,3,16,16]);mil_op(block,'upsample_nearest_neighbor','output',{'x':'restored','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','spacebatch');data=np.random.default_rng(25).random((1,3,16,16),dtype=np.float32);yy,xx=np.indices((16,16));expected=data+(((yy+1)%2)*3+(xx+1)%3).astype(np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/spacebatch.onnx',data),np.repeat(np.repeat(expected,2,2),2,3))
    def test_mil_while_loop_checks_initial_condition_and_updates_multiple_values(self):
        for iterations in (0,3):
            spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'zero',0,MIL_pb2.INT32);mil_const(block,'one',1,MIL_pb2.INT32);mil_const(block,'limit',iterations,MIL_pb2.INT32);mil_const(block,'factor',1.25);mil_const(block,'two',2.0);loop=mil_op(block,'while_loop','selected',{'loop_vars':['input','zero']},[1,3,16,16]);out=loop.outputs.add();out.name='final_count';tensor(out.type,MIL_pb2.INT32,[])
            condition=loop.blocks.add();condition.outputs.append('keep_going');body=loop.blocks.add();body.outputs.extend(['result','next_count'])
            for target in (condition,body):
                value=target.inputs.add();value.name='value';tensor(value.type,MIL_pb2.FLOAT32,[1,3,16,16]);counter=target.inputs.add();counter.name='counter';tensor(counter.type,MIL_pb2.INT32,[])
            mil_op(condition,'less','keep_going',{'x':'counter','y':'limit'},[],MIL_pb2.BOOL);mil_op(body,'mul','result',{'x':'value','y':'factor'},[1,3,16,16]);mil_op(body,'add','next_count',{'x':'counter','y':'one'},[],MIL_pb2.INT32);mil_op(block,'upsample_nearest_neighbor','output',{'x':'selected','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','while');data=np.random.default_rng(26).random((1,3,16,16),dtype=np.float32);expected=data
            for _ in range(iterations):expected=expected*np.float32(1.25)
            np.testing.assert_array_equal(self.run_model(self.root/'out/while.onnx',data),np.repeat(np.repeat(expected,2,2),2,3))
    def test_public_scatter_modes_apply_duplicate_indices_in_order(self):
        from onnx import helper as h,TensorProto as t
        data=np.arange(1,1+3*16*16,dtype=np.float32).reshape(1,3,16,16)/100
        updates=np.broadcast_to(np.array([1.2,2.4,0.6],dtype=np.float32).reshape(1,3,1,1),data.shape).copy()
        for kind in ('scatter','scatter_nd','scatter_along_axis'):
            for mode in ('update','add','sub','mul','div','max','min'):
                with self.subTest(operator=kind,mode=mode):
                    graph=Graph();graph.shapes['input']=list(data.shape);graph.types['output']='FLOAT32';graph.shapes['output']=list(data.shape);indices=np.array([2,0,2],dtype=np.int32) if kind=='scatter' else np.array([[0,2],[0,0],[0,2]],dtype=np.int32) if kind=='scatter_nd' else np.broadcast_to(np.array([2,0,2],dtype=np.int32).reshape(1,3,1,1),data.shape).copy();value=updates[0] if kind=='scatter_nd' else updates;graph.scatter('input',graph.const(indices),graph.const(value),['output'],kind,1,mode)
                    model=h.make_model(h.make_graph(graph.nodes,'scatter',[h.make_tensor_value_info('input',t.FLOAT,list(data.shape))],[h.make_tensor_value_info('output',t.FLOAT,list(data.shape))],list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10;actual=ort.InferenceSession(model.SerializeToString()).run(None,{'input':data})[0];expected=data.copy()
                    for source,destination in enumerate([2,0,2]):
                        old=expected[:,destination];update=updates[:,source];expected[:,destination]={'update':lambda:update,'add':lambda:old+update,'sub':lambda:old-update,'mul':lambda:old*update,'div':lambda:old/update,'max':lambda:np.maximum(old,update),'min':lambda:np.minimum(old,update)}[mode]()
                    np.testing.assert_array_equal(actual,expected)
    def test_mil_dynamic_shape_negative_squeeze_and_reverse_exclusive_cumsum(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'begin',[-1,0,0,0],MIL_pb2.INT32);mil_const(block,'end',[0,3,16,16],MIL_pb2.INT32);mil_const(block,'squeeze',[True,False,False,False],MIL_pb2.BOOL);mil_const(block,'zero',[0],MIL_pb2.INT32);mil_const(block,'axis',3,MIL_pb2.INT32);mil_const(block,'yes',True,MIL_pb2.BOOL);mil_op(block,'slice_by_index','last',{'x':'input','begin':'begin','end':'end','squeeze_mask':'squeeze'},[3,16,16]);mil_op(block,'expand_dims','restored',{'x':'last','axes':'zero'},[1,3,16,16]);mil_op(block,'shape','shape',{'x':'input'},[4],MIL_pb2.INT32);mil_op(block,'reshape','reshaped',{'x':'restored','shape':'shape'},[1,3,16,16]);mil_op(block,'cumsum','output',{'x':'reshaped','axis':'axis','exclusive':'yes','reverse':'yes'},[1,3,16,16]);convert(self.write(spec),self.root/'out','dynamic');data=np.random.default_rng(27).random((1,3,16,16),dtype=np.float32);reverse=data[:,:,:,::-1];expected=(np.cumsum(reverse,axis=3)-reverse)[:,:,:,::-1];np.testing.assert_allclose(self.run_model(self.root/'out/dynamic.onnx',data),expected,rtol=2e-6,atol=2e-6)
    def test_nn_dynamic_shapes_in_place_names_and_slice_padding_match_numpy(self):
        spec=nn_spec();del spec.neuralNetwork.layers[:]
        def layer(kind,inputs,output):l=spec.neuralNetwork.layers.add();l.name=kind;l.input.extend(inputs);l.output.append(output);return getattr(l,kind)
        layer('getShape',['input'],'shape').SetInParent();layer('reshapeDynamic',['input','shape'],'value').SetInParent();layer('activation',['value'],'value').ReLU.SetInParent();p=layer('sliceStatic',['value'],'cropped');p.beginIds.extend([0,0,1,1]);p.endIds.extend([1,3,-1,-1]);p.strides.extend([1]*4);p=layer('constantPad',['cropped'],'output');p.padAmounts.extend([0,0,0,0,1,1,1,1]);p.value=0.2;convert(self.write(spec),self.root/'out','slice');data=np.random.default_rng(28).normal(size=(1,3,16,16)).astype(np.float32);expected=np.pad(np.maximum(data[:,:,1:-1,1:-1],0),[(0,0),(0,0),(1,1),(1,1)],constant_values=0.2);np.testing.assert_array_equal(self.run_model(self.root/'out/slice.onnx',data),expected)
    def test_nn_3d_convolution_and_pooling_match_independent_numpy(self):
        spec=nn_spec();del spec.neuralNetwork.layers[:]
        l=spec.neuralNetwork.layers.add();l.name='to3d';l.input.append('input');l.output.append('volume');l.reshapeStatic.targetShape.extend([1,3,1,16,16]);l=spec.neuralNetwork.layers.add();l.name='conv3d';l.input.append('volume');l.output.append('convolved');p=l.convolution3d;p.inputChannels=p.outputChannels=3;p.nGroups=1;p.kernelDepth=p.kernelHeight=p.kernelWidth=1;p.paddingType=1;p.weights.floatValue.extend(np.eye(3,dtype=np.float32).flatten());l=spec.neuralNetwork.layers.add();l.name='pool3d';l.input.append('convolved');l.output.append('pooled');p=l.pooling3d;p.type=1;p.kernelDepth=1;p.kernelHeight=p.kernelWidth=2;p.strideDepth=1;p.strideHeight=p.strideWidth=2;p.paddingType=1;l=spec.neuralNetwork.layers.add();l.name='to2d';l.input.append('pooled');l.output.append('image');l.reshapeStatic.targetShape.extend([1,3,8,8]);l=spec.neuralNetwork.layers.add();l.name='restore';l.input.append('image');l.output.append('output');l.upsample.scalingFactor.extend([2,2]);convert(self.write(spec),self.root/'out','threeD');data=np.random.default_rng(29).random((1,3,16,16),dtype=np.float32);expected=data.reshape(1,3,8,2,8,2).mean(axis=(3,5));np.testing.assert_allclose(self.run_model(self.root/'out/threeD.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-6,atol=1e-7)
    def test_nn_parametric_softplus_preserves_channel_parameters(self):
        spec=nn_spec();activation=spec.neuralNetwork.layers[1].activation;activation.ClearField('ReLU');activation.parametricSoftplus.alpha.floatValue.extend([1,2,3]);activation.parametricSoftplus.beta.floatValue.extend([0.1,0.2,0.3]);convert(self.write(spec),self.root/'out','parametric');data=np.random.default_rng(30).normal(size=(1,3,16,16)).astype(np.float32);expected=np.array([1,2,3],dtype=np.float32).reshape(1,3,1,1)*np.logaddexp(0,data*np.array([0.1,0.2,0.3],dtype=np.float32).reshape(1,3,1,1));np.testing.assert_allclose(self.run_model(self.root/'out/parametric.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-6,atol=1e-7)
    def test_mil_channel_quantization_rounding_clipping_and_dequantization(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'scale',[0.01,0.02,0.03]);mil_const(block,'zero_point',[-3,4,10],MIL_pb2.INT8);mil_const(block,'axis',1,MIL_pb2.INT32);mil_const(block,'dtype','int8',MIL_pb2.STRING);mil_op(block,'quantize','quantized',{'input':'input','scale':'scale','zero_point':'zero_point','axis':'axis','output_dtype':'dtype'},[1,3,16,16],MIL_pb2.INT8);mil_op(block,'dequantize','output',{'input':'quantized','scale':'scale','zero_point':'zero_point','axis':'axis'},[1,3,16,16]);convert(self.write(spec),self.root/'out','quantization');scale=np.array([0.01,0.02,0.03],dtype=np.float32).reshape(1,3,1,1);zero=np.array([-3,4,10],dtype=np.float32).reshape(1,3,1,1);data=np.linspace(-4,4,3*16*16,dtype=np.float32).reshape(1,3,16,16);expected=(np.clip(np.round(data/scale)+zero,-128,127)-zero)*scale;np.testing.assert_array_equal(self.run_model(self.root/'out/quantization.onnx',data),expected)
    def test_mil_masked_attention_matches_numpy_softmax_and_matmul(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mask=np.tri(16,dtype=bool).reshape(1,1,16,16);mil_const(block,'mask',mask,MIL_pb2.BOOL);mil_op(block,'scaled_dot_product_attention','output',{'query':'input','key':'input','value':'input','attn_mask':'mask'},[1,3,16,16]);convert(self.write(spec),self.root/'out','attention');data=np.random.default_rng(31).random((1,3,16,16),dtype=np.float32);scores=np.matmul(data,data.swapaxes(-1,-2))/4;scores=np.where(mask,scores,-np.inf);exp=np.exp(scores-np.max(scores,axis=-1,keepdims=True));expected=np.matmul(exp/exp.sum(axis=-1,keepdims=True),data);np.testing.assert_allclose(self.run_model(self.root/'out/attention.onnx',data),expected,rtol=2e-6,atol=2e-7)
    def test_mil_new_resize_uses_runtime_shape_prefix_and_nearest_pixels(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'two',2,MIL_pb2.INT32);mil_const(block,'dimensions',2,MIL_pb2.UINT32);mil_const(block,'nearest','NEAREST_NEIGHBOR',MIL_pb2.STRING);mil_op(block,'shape','shape',{'x':'input'},[4],MIL_pb2.INT32);mil_op(block,'mul','target',{'x':'shape','y':'two'},[4],MIL_pb2.INT32);mil_op(block,'resize','output',{'x':'input','shape':'target','resized_dims':'dimensions','interpolation_mode':'nearest'},[1,3,32,32]);convert(self.write(spec),self.root/'out','resize');data=np.random.default_rng(32).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/resize.onnx',data),np.repeat(np.repeat(data,2,2),2,3))
    def test_pipeline_nested_mil_branch_captures_remain_in_stage_scope(self):
        child=mil_spec();block=child.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'half',0.5);mil_const(block,'two',2.0);mil_const(block,'yes',True,MIL_pb2.BOOL);condition=mil_op(block,'cond','selected',{'pred':'yes'},[1,3,16,16])
        for factor in ('two','half'):branch=condition.blocks.add();branch.outputs.append('result');mil_op(branch,'mul','result',{'x':'input','y':factor},[1,3,16,16])
        mil_op(block,'upsample_nearest_neighbor','output',{'x':'selected','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);spec=Model_pb2.Model();spec.specificationVersion=6;feature(spec,'input',[1,3,16,16]);feature(spec,'output',[1,3,32,32],True);spec.pipeline.models.add().CopyFrom(child);identity=spec.pipeline.models.add();identity.specificationVersion=4;feature(identity,'output',[1,3,32,32]);feature(identity,'output',[1,3,32,32],True);identity.identity.SetInParent();convert(self.write(spec),self.root/'out','nested');data=np.random.default_rng(33).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/nested.onnx',data),np.repeat(np.repeat(data*2,2,2),2,3))
    def test_mil_tensor_lists_grow_and_apply_repeated_scatter_indices(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:]
        for name,value in [('zero',0),('three',3),('sixteen',16),('two',2),('four',4)]:mil_const(block,name,value,MIL_pb2.INT32)
        mil_const(block,'axis',[0],MIL_pb2.INT32);mil_const(block,'indices',[4,2,4],MIL_pb2.INT32);mil_const(block,'selected_indices',[4,2],MIL_pb2.INT32);mil_const(block,'bias',np.array([1,2,3],dtype=np.float32).reshape(3,1,1,1));mil_const(block,'float','fp32',MIL_pb2.STRING)
        def list_op(kind,name,args):
            op=mil_op(block,kind,name,args,[]);out=op.outputs[0];out.type.ClearField('tensorType');tensor(out.type.listType.type,MIL_pb2.FLOAT32,[3,16,16]);return op
        list_op('make_list','empty',{'init_length':'zero','elem_shape':['three','sixteen','sixteen']});mil_op(block,'squeeze','element',{'x':'input','axes':'axis'},[3,16,16]);list_op('list_write','written',{'ls':'empty','index':'two','value':'element'});mil_op(block,'concat','repeated',{'values':['input','input','input'],'axis':'zero'},[3,3,16,16]);mil_op(block,'mul','updates',{'x':'repeated','y':'bias'},[3,3,16,16]);list_op('list_scatter','scattered',{'ls':'written','indices':'indices','value':'updates'});mil_op(block,'list_gather','gathered',{'ls':'scattered','indices':'selected_indices'},[2,3,16,16]);mil_op(block,'reduce_sum','total',{'x':'gathered','axes':'axis'},[3,16,16]);mil_op(block,'expand_dims','restored',{'x':'total','axes':'axis'},[1,3,16,16]);mil_op(block,'list_length','length',{'ls':'scattered'},[],MIL_pb2.INT32);mil_op(block,'cast','length_float',{'x':'length','dtype':'float'},[]);mil_op(block,'mul','output',{'x':'restored','y':'length_float'},[1,3,16,16]);convert(self.write(spec),self.root/'out','lists');data=np.random.default_rng(34).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/lists.onnx',data),(data*3+data*2)*5)
    def test_nn_recurrent_graphs_match_independent_gate_equations_and_states(self):
        from onnx import helper as h,TensorProto as t
        rng=np.random.default_rng(35);data=rng.normal(size=(4,2,3,1,1)).astype(np.float32);initial=rng.normal(size=(2,3)).astype(np.float32)*0.1;initial_c=rng.normal(size=(2,3)).astype(np.float32)*0.1
        sigmoid=lambda value:1/(1+np.exp(-value))
        for kind in ('simpleRecurrent','gru','uniDirectionalLSTM'):
            for reverse in (False,True):
                for sequence in (False,True):
                    with self.subTest(kind=kind,reverse=reverse,sequence=sequence):
                        layer=NeuralNetwork_pb2.NeuralNetworkLayer();p=getattr(layer,kind);p.inputVectorSize=p.outputVectorSize=3;p.reverseInput=reverse;gates=1 if kind=='simpleRecurrent' else 3 if kind=='gru' else 4;weights=[rng.normal(size=(3,3)).astype(np.float32)*0.2 for _ in range(gates)];recursion=[rng.normal(size=(3,3)).astype(np.float32)*0.1 for _ in range(gates)];bias=[rng.normal(size=(3,)).astype(np.float32)*0.05 for _ in range(gates)]
                        if kind=='simpleRecurrent':p.sequenceOutput=sequence;p.hasBiasVector=True;p.activation.tanh.SetInParent();p.weightMatrix.floatValue.extend(weights[0].flatten());p.recursionMatrix.floatValue.extend(recursion[0].flatten());p.biasVector.floatValue.extend(bias[0]);prefixes=[]
                        elif kind=='gru':p.sequenceOutput=sequence;p.hasBiasVectors=True;p.activations.add().sigmoid.SetInParent();p.activations.add().tanh.SetInParent();prefixes=['resetGate','outputGate','updateGate'];owner=p
                        else:p.params.sequenceOutput=sequence;p.params.hasBiasVectors=True;p.params.forgetBias=True;p.params.hasPeepholeVectors=True;p.params.cellClipThreshold=0.4;p.activations.add().sigmoid.SetInParent();p.activations.add().tanh.SetInParent();p.activations.add().tanh.SetInParent();prefixes=['inputGate','forgetGate','outputGate','blockInput'];owner=p.weightParams
                        for index,prefix in enumerate(prefixes):getattr(owner,prefix+'WeightMatrix').floatValue.extend(weights[index].flatten());getattr(owner,prefix+'RecursionMatrix').floatValue.extend(recursion[index].flatten());getattr(owner,prefix+'BiasVector').floatValue.extend(bias[index])
                        peephole=[np.full(3,value,dtype=np.float32) for value in (0.05,0.06,0.07)]
                        if kind=='uniDirectionalLSTM':
                            for prefix,value in zip(prefixes[:3],peephole):getattr(owner,prefix+'PeepholeVector').floatValue.extend(value)
                        graph=Graph();graph.shapes['input']=list(data.shape);inputs=['input',graph.const(initial.reshape(1,2,3,1,1))];outputs=['output','hidden']
                        if kind=='uniDirectionalLSTM':inputs.append(graph.const(initial_c.reshape(1,2,3,1,1)));outputs.append('cell')
                        lower_nn_recurrent(graph,kind,p,inputs,outputs);infos=[h.make_tensor_value_info('output',t.FLOAT,[4 if sequence else 1,2,3,1,1])]+[h.make_tensor_value_info(name,t.FLOAT,[1,2,3,1,1]) for name in outputs[1:]];model=h.make_model(h.make_graph(graph.nodes,'recurrent',[h.make_tensor_value_info('input',t.FLOAT,list(data.shape))],infos,list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10;actual=ort.InferenceSession(model.SerializeToString()).run(None,{'input':data});state=initial.copy();cell=initial_c.copy();history=[]
                        for step in data[::-1] if reverse else data:
                            x=step[:,:,0,0];affine=lambda gate,current=state:x@weights[gate].T+current@recursion[gate].T+bias[gate]
                            if kind=='simpleRecurrent':state=np.tanh(np.clip(affine(0),-50,50))
                            elif kind=='gru':reset=sigmoid(np.clip(affine(0),-50,50));update=sigmoid(np.clip(affine(2),-50,50));candidate=np.tanh(np.clip(affine(1,state*reset),-50,50));state=(1-update)*candidate+update*state
                            else:
                                input_gate=sigmoid(np.clip(affine(0)+peephole[0]*cell,-50,50));forget=sigmoid(np.clip(affine(1)+peephole[1]*cell+1,-50,50));candidate=np.tanh(np.clip(affine(3),-50,50));cell=np.clip(forget*cell+input_gate*candidate,-0.4,0.4);output_gate=sigmoid(np.clip(affine(2)+peephole[2]*cell,-50,50));state=np.tanh(cell)*output_gate
                            history.append(state)
                        expected=np.stack(history if sequence else [state])[:,:,:,None,None];np.testing.assert_allclose(actual[0],expected,rtol=4e-6,atol=1e-7);np.testing.assert_allclose(actual[1].reshape(2,3),state,rtol=4e-6,atol=1e-7)
                        if kind=='uniDirectionalLSTM':np.testing.assert_allclose(actual[2].reshape(2,3),cell,rtol=4e-6,atol=1e-7)
    def test_mil_slice_update_preserves_unselected_values_with_dynamic_negative_bounds(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'offset',[-2,-3,-15,-17],MIL_pb2.INT32);mil_const(block,'end',[0,3,15,0],MIL_pb2.INT32);mil_const(block,'stride',[1,1,2,-2],MIL_pb2.INT32);mil_const(block,'squeeze',[True,False,False,False],MIL_pb2.BOOL);mil_const(block,'end_mask',[False,False,False,True],MIL_pb2.BOOL);update=np.arange(3*7*8,dtype=np.float32).reshape(3,7,8)/100;mil_const(block,'update',update);mil_op(block,'shape','shape',{'x':'input'},[4],MIL_pb2.INT32);mil_op(block,'add','begin',{'x':'shape','y':'offset'},[4],MIL_pb2.INT32);mil_op(block,'slice_update','updated',{'x':'input','update':'update','begin':'begin','end':'end','stride':'stride','squeeze_mask':'squeeze','end_mask':'end_mask'},[1,3,16,16]);mil_const(block,'two',2.0);mil_op(block,'upsample_nearest_neighbor','output',{'x':'updated','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','updated');data=np.random.default_rng(37).random((1,3,16,16),dtype=np.float32);expected=data.copy();expected[-1,:,1:15:2,::-2]=update;np.testing.assert_array_equal(self.run_model(self.root/'out/updated.onnx',data),np.repeat(np.repeat(expected,2,2),2,3))
    def test_block_vector_palette_and_sparse_quantization_match_explicit_numpy(self):
        indices=np.array([[0,1],[1,0]],dtype=np.uint8);lut=np.arange(2*1*2*2,dtype=np.float32).reshape(2,1,2,2);expected=np.empty((4,2),dtype=np.float32)
        for row in range(2):
            for column in range(2):expected[row*2:row*2+2,column]=lut[row,0,indices[row,column]]
        np.testing.assert_array_equal(decompress_lut(indices,lut,0),expected)
        mask=np.zeros((1,3,16,16),dtype=np.uint8);mask[:,:,::2,::2]=1;packed=np.arange(np.count_nonzero(mask),dtype=np.uint8)%4;palette=np.array([0.25,0.5,0.75,1],dtype=np.float32).reshape(1,1,1,1,4,1);scale=np.array([0.5,1,1.5],dtype=np.float32).reshape(1,3,1,1);offset=np.array([0.1,0.2,0.3],dtype=np.float32).reshape(1,3,1,1);spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:]
        for name,value,dtype in [('mask',mask,MIL_pb2.UINT8),('indices',packed,MIL_pb2.UINT8),('palette',palette,MIL_pb2.FLOAT32),('scale',scale,MIL_pb2.FLOAT32),('offset',offset,MIL_pb2.FLOAT32)]:mil_const(block,name,value,dtype)
        op=mil_op(block,'constexpr_lut_to_sparse','palette_mask',{'indices_mask':'mask','indices_nonzero_data':'indices','lut':'palette'},mask.shape,MIL_pb2.UINT8);out=op.outputs.add();out.name='palette_data';tensor(out.type,MIL_pb2.FLOAT32,[packed.size]);op=mil_op(block,'constexpr_sparse_blockwise_shift_scale','scaled_mask',{'data_mask':'palette_mask','nonzero_data':'palette_data','scale':'scale','offset':'offset'},mask.shape,MIL_pb2.UINT8);out=op.outputs.add();out.name='scaled_data';tensor(out.type,MIL_pb2.FLOAT32,[packed.size]);mil_op(block,'constexpr_sparse_to_dense','weights',{'mask':'scaled_mask','nonzero_data':'scaled_data'},mask.shape);mil_op(block,'mul','weighted',{'x':'input','y':'weights'},mask.shape);mil_const(block,'two',2.0);mil_op(block,'upsample_nearest_neighbor','output',{'x':'weighted','scale_factor_height':'two','scale_factor_width':'two'},[1,3,32,32]);convert(self.write(spec),self.root/'out','sparse');data=np.random.default_rng(38).random(mask.shape,dtype=np.float32);dense=np.zeros(mask.shape,dtype=np.float32);dense[mask.astype(bool)]=palette.flatten()[packed];expected=np.where(mask,(dense-offset)*scale,0);np.testing.assert_array_equal(self.run_model(self.root/'out/sparse.onnx',data),np.repeat(np.repeat(data*expected,2,2),2,3))
    def test_crop_resize_coordinate_modes_sampling_and_batch_selection_match_pixels(self):
        from onnx import helper as h,TensorProto as t
        data=np.array([[[[0,1,4,9],[2,3,6,11],[8,9,12,17]]],[[[20,21,24,29],[22,23,26,31],[28,29,32,37]]]],dtype=np.float32)
        for mode in ('DEFAULT','STRICT_ALIGN_CORNERS','ALIGN_CORNERS','OFFSET_CORNERS','UNALIGN_CORNERS'):
            for order in ('CORNERS_HEIGHT_FIRST','CORNERS_WIDTH_FIRST','CENTER_SIZE_HEIGHT_FIRST','CENTER_SIZE_WIDTH_FIRST'):
                for selected in (False,True):
                    with self.subTest(mode=mode,order=order,selected=selected):
                        start=np.array([0.25,0.5],dtype=np.float32);end=np.array([1.75,2.5],dtype=np.float32);coords=np.concatenate([(start+end)/2,end-start]) if order.startswith('CENTER') else np.concatenate([start,end]);coords=coords[[1,0,3,2]] if order.endswith('WIDTH_FIRST') else coords;coords=np.concatenate([[1],coords]) if selected else coords;roi=coords.astype(np.float32).reshape(1,1,-1,1,1);graph=Graph();graph.shapes['input']=list(data.shape);graph.types['input']='FLOAT32';graph.crop_resize('input',graph.const(roi),['output'],1 if mode=='ALIGN_CORNERS' else 4,5,box_mode=order,sampling=mode);shape=graph.shapes['output'];model=h.make_model(h.make_graph(graph.nodes,'crop',[h.make_tensor_value_info('input',t.FLOAT,list(data.shape))],[h.make_tensor_value_info('output',t.FLOAT,shape)],list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10;actual=ort.InferenceSession(model.SerializeToString()).run(None,{'input':data})[0]
                        def positions(a,b,n):
                            span=b-a;size=span+1
                            if mode in ('STRICT_ALIGN_CORNERS','ALIGN_CORNERS'):p=np.array([span/2]) if mode=='ALIGN_CORNERS' and n==1 else np.arange(n)*span/max(n-1,1)
                            elif mode=='OFFSET_CORNERS':p=(np.arange(n)+0.5)*max(1,span)/n
                            elif mode=='UNALIGN_CORNERS':p=(np.arange(n)+0.5)*size/n-0.5
                            else:p=np.arange(n)*size/n
                            return a+np.clip(p,0,span)
                        yy=positions(start[0],end[0],shape[-2]);xx=positions(start[1],end[1],shape[-1]);expected=np.empty(shape,dtype=np.float32)
                        for batch in range(shape[1]):
                            image=data[1 if selected else batch,0]
                            for row,y in enumerate(yy):
                                for column,x in enumerate(xx):yl=int(np.floor(y));xl=int(np.floor(x));yh=min(yl+1,2);xh=min(xl+1,3);expected[0,batch,0,row,column]=(image[yl,xl]*(1-(x-xl))+image[yl,xh]*(x-xl))*(1-(y-yl))+(image[yh,xl]*(1-(x-xl))+image[yh,xh]*(x-xl))*(y-yl)
                        np.testing.assert_allclose(actual,expected,rtol=1e-6,atol=4e-6)
    def test_nms_per_class_fallback_padding_and_runtime_limit_match_greedy_reference(self):
        from onnx import helper as h,TensorProto as t
        boxes=np.array([[[0,0,2,2],[0.1,0.1,2,2],[0.2,0.2,2,2],[5,5,2,2]],[[0,0,2,2],[3,3,2,2],[6,6,2,2],[9,9,2,2]]],dtype=np.float32);scores=np.array([[[0.9,0.1],[0.1,0.8],[0.7,0.1],[0.6,0.2]],[[0.05,0.02],[0.01,0.02],[0.04,0.01],[0.02,0.03]]],dtype=np.float32)
        for per_class in (False,True):
            graph=Graph();graph.shapes['input']=list(boxes.shape);graph.types['input']='FLOAT32';outputs=['boxes','scores','indices','count'];graph.nms('input',graph.const(scores),outputs,graph.const(0.4,dtype=np.float32),graph.const(0.2,dtype=np.float32),graph.const([3],dtype=np.float32),per_class,True);infos=[h.make_tensor_value_info(name,t.FLOAT if i<2 else t.INT32,[[2,3,4],[2,3,2],[2,3],[2]][i]) for i,name in enumerate(outputs)];model=h.make_model(h.make_graph(graph.nodes,'nms',[h.make_tensor_value_info('input',t.FLOAT,list(boxes.shape))],infos,list(graph.initializers.values())),opset_imports=[h.make_opsetid('',17)]);model.ir_version=10;actual=ort.InferenceSession(model.SerializeToString()).run(None,{'input':boxes});expected=[0,1,3] if per_class else [0,3];np.testing.assert_array_equal(actual[2][0],expected+[-1]*(3-len(expected)));np.testing.assert_array_equal(actual[2][1],[0,-1,-1]);np.testing.assert_array_equal(actual[3],[len(expected),1]);np.testing.assert_array_equal(actual[0][0,:len(expected)],boxes[0,expected]);np.testing.assert_array_equal(actual[1][0,:len(expected)],scores[0,expected]);np.testing.assert_array_equal(actual[0][1,1:],0);np.testing.assert_array_equal(actual[1][1,1:],0)
    def test_nn_spatial_layers_preserve_rank_three_and_five_batch_dimensions(self):
        for shape in ([3,16,16],[1,1,3,16,16]):
            spec=nn_spec();network=spec.neuralNetwork;network.layers[0].input[0]='ranked';first=NeuralNetwork_pb2.NeuralNetworkLayer();first.name='rank';first.input.append('input');first.output.append('ranked');first.reshapeStatic.targetShape.extend(shape);network.layers.insert(0,first);del network.layers[3:];l=network.layers.add();l.name='pool';l.input.append('clamped');l.output.append('pooled');p=l.pooling;p.type=1;p.kernelSize.extend([2,2]);p.stride.extend([2,2]);p.valid.SetInParent();l=network.layers.add();l.name='upsample';l.input.append('pooled');l.output.append('upscaled');l.upsample.scalingFactor.extend([2,2]);l=network.layers.add();l.name='canonical';l.input.append('upscaled');l.output.append('output');l.reshapeStatic.targetShape.extend([1,3,16,16]);convert(self.write(spec),self.root/'out','ranks');data=np.random.default_rng(39).random((1,3,16,16),dtype=np.float32);expected=data.reshape(1,3,8,2,8,2).mean(axis=(3,5));np.testing.assert_allclose(self.run_model(self.root/'out/ranks.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-6,atol=1e-7)
    def test_dynamic_bilinear_resize_offset_and_singleton_alignment(self):
        data=np.broadcast_to(np.arange(16,dtype=np.float32),(1,3,16,16)).copy()
        for mode,target in [('OFFSET_CORNERS',[32,32]),('ALIGN_CORNERS',[1,32])]:
            spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'target',target,MIL_pb2.INT32);mil_const(block,'axes',[2,3],MIL_pb2.INT32);mil_const(block,'dims',2,MIL_pb2.UINT32);mil_const(block,'mode',mode,MIL_pb2.STRING);mil_op(block,'shape','shape',{'x':'input'},[4],MIL_pb2.INT32);mil_op(block,'gather','spatial',{'x':'shape','indices':'axes'},[2],MIL_pb2.INT32);mil_const(block,'zero',0,MIL_pb2.INT32);mil_op(block,'mul','zero_shape',{'x':'spatial','y':'zero'},[2],MIL_pb2.INT32);mil_op(block,'add','dynamic_target',{'x':'zero_shape','y':'target'},[2],MIL_pb2.INT32);mil_op(block,'resize','output',{'x':'input','shape':'dynamic_target','resized_dims':'dims','sampling_mode':'mode'},[1,3]+target);convert(self.write(spec),self.root/'out','sampled',allow_general=target[0]==1);actual=self.run_model(self.root/'out/sampled.onnx',data);positions=(np.arange(32)+0.5)*15/32 if mode=='OFFSET_CORNERS' else np.linspace(0,15,32);np.testing.assert_allclose(actual,np.broadcast_to(positions,(1,3,target[0],32)),rtol=1e-6,atol=1e-6)
    def test_nn_legacy_five_dimensional_mapping_preserves_channel_axes(self):
        spec=nn_spec();spec.specificationVersion=3;spec.description.input[0].type.multiArrayType.shape[:]=[3,16,16];spec.description.output[0].type.multiArrayType.shape[:]=[3,32,32];l=spec.neuralNetwork.layers[1];l.ClearField('activation');l.softmax.SetInParent();convert(self.write(spec),self.root/'out','legacy_mapping');data=np.random.default_rng(40).random((1,3,16,16),dtype=np.float32);exp=np.exp(data-data.max(axis=1,keepdims=True));expected=exp/exp.sum(axis=1,keepdims=True);np.testing.assert_allclose(self.run_model(self.root/'out/legacy_mapping.onnx',data),np.repeat(np.repeat(expected,2,2),2,3),rtol=1e-6,atol=1e-7)
    def test_nn_loop_exposes_values_first_created_inside_body(self):
        spec=nn_spec();del spec.neuralNetwork.layers[:]
        p=spec.neuralNetwork.layers.add();p.name='counter';p.output.append('count');p.loadConstantND.shape.append(1);p.loadConstantND.data.floatValue.append(0);loop=spec.neuralNetwork.layers.add().loop;loop.maxLoopIterations=3;l=loop.bodyNetwork.layers.add();l.name='increment';l.input.append('count');l.output.append('count');l.add.alpha=1;l=loop.bodyNetwork.layers.add();l.name='derived';l.input.extend(['input','count']);l.output.append('inside');l.multiply.SetInParent();l=spec.neuralNetwork.layers.add();l.name='result';l.input.append('inside');l.output.append('output');l.copy.SetInParent();convert(self.write(spec),self.root/'out','created');data=np.random.default_rng(41).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/created.onnx',data),data*3)
    def test_nn_weighted_matmul_column_storage_ignores_two_input_flags_and_decodes_signed_weights(self):
        spec=nn_spec();network=spec.neuralNetwork;network.arrayInputShapeMapping=1;del network.layers[:];data=np.random.default_rng(42).random((1,3,16,16),dtype=np.float32)
        l=network.layers.add();l.name='rows';l.input.append('input');l.output.append('rows');l.reshapeStatic.targetShape.extend([1,1,256,3]);l=network.layers.add();l.name='weighted';l.input.append('rows');l.output.append('product');p=l.batchedMatmul;p.weightMatrixFirstDimension=3;p.weightMatrixSecondDimension=3;p.transposeA=True;p.transposeB=True;weight=np.array([[1,2,3],[-4,5,-6],[7,-8,9]],dtype=np.int8);p.weights.int8RawValue=weight.T.tobytes();p.weights.quantization.numberOfBits=8;p.weights.quantization.linearQuantization.scale.append(0.1);p.int8DynamicQuantize=True;l=network.layers.add();l.name='restore';l.input.append('product');l.output.append('output');l.reshapeStatic.targetShape.extend(data.shape);report=convert(self.write(spec),self.root/'out','signed');expected=(data.reshape(1,1,256,3)@(weight.astype(np.float32)*np.float32(0.1))).reshape(data.shape);np.testing.assert_allclose(self.run_model(self.root/'out/signed.onnx',data),expected,rtol=1e-6,atol=1e-7);self.assertTrue(any('precisionNote' in row for row in report['graph']))
    def test_mil_batched_gather_negative_int16_indices_and_channel_prelu(self):
        spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];indices=np.stack([np.arange(15,-1,-1),np.arange(16),np.roll(np.arange(16),3)]).astype(np.int16);indices[0,0]=-1;mil_const(block,'indices',indices,MIL_pb2.INT16);mil_const(block,'axis',3,MIL_pb2.INT32);mil_const(block,'batch',2,MIL_pb2.INT32);mil_const(block,'alphas',[0.1,0.2,0.3]);mil_const(block,'offset',0.5);mil_op(block,'sub','centered',{'x':'input','y':'offset'},[1,3,16,16]);mil_op(block,'prelu','activated',{'x':'centered','alpha':'alphas'},[1,3,16,16]);mil_const(block,'with_batch',indices.reshape(1,3,16),MIL_pb2.INT16);mil_op(block,'gather','output',{'x':'activated','indices':'with_batch','axis':'axis','batch_dims':'batch'},[1,3,16,16]);convert(self.write(spec),self.root/'out','batch');data=np.random.default_rng(43).random((1,3,16,16),dtype=np.float32);centered=data-0.5;activated=np.where(centered>=0,centered,centered*np.array([0.1,0.2,0.3],dtype=np.float32).reshape(1,3,1,1));expected=np.stack([activated[0,c,:,indices[c]].T for c in range(3)])[None];np.testing.assert_allclose(self.run_model(self.root/'out/batch.onnx',data),expected,rtol=1e-6,atol=1e-7)
    def test_nn_interleaved_concat_runtime_topk_and_reduce_all(self):
        spec=nn_spec();network=spec.neuralNetwork;network.arrayInputShapeMapping=1;del network.layers[:];data=np.random.default_rng(44).random((1,3,16,16),dtype=np.float32)
        def layer(kind,inputs,outputs):l=network.layers.add();l.name=kind;l.input.extend(inputs);l.output.extend(outputs);return getattr(l,kind)
        layer('multiply',['input'],['twice']).alpha=2;p=layer('concatND',['input','twice'],['joined']);p.axis=3;p.interleave=True;p=layer('loadConstantND',[],['K']);p.shape.append(1);p.data.floatValue.append(5);p=layer('topK',['joined','K'],['top','indices']);p.axis=3;p.K=1;p=layer('reduceMean',['top'],['output']);p.axes.append(1);p.reduceAll=True;p.keepDims=True;convert(self.write(spec),self.root/'out','joined',allow_general=True);expected=np.sort(np.stack([data,data*2],axis=-1).reshape(1,3,16,32),axis=-1)[...,-5:].mean(keepdims=True);np.testing.assert_allclose(self.run_model(self.root/'out/joined.onnx',data),expected,rtol=1e-6,atol=1e-7)
    def test_nn_legacy_permute_load_constant_spatial_padding_and_pool_defaults(self):
        spec=nn_spec();spec.specificationVersion=3;spec.description.input[0].type.multiArrayType.shape[:]=[3,16,16];spec.description.output[0].type.multiArrayType.shape[:]=[3,16,16];network=spec.neuralNetwork;del network.layers[:]
        def layer(kind,inputs,outputs):l=network.layers.add();l.name=kind;l.input.extend(inputs);l.output.extend(outputs);return getattr(l,kind)
        p=layer('loadConstant',[],['offset']);p.shape.extend([3,1,1]);p.data.floatValue.extend([1,2,3]);layer('add',['input','offset'],['offsetted']).SetInParent();p=layer('permute',['offsetted'],['transposed']);p.axis.extend([0,1,3,2]);p=layer('padding',['transposed'],['padded']);p.constant.value=0
        for _ in range(2):b=p.paddingAmounts.borderAmounts.add();b.startEdgeSize=1;b.endEdgeSize=1
        p=layer('pooling',['padded'],['output']);p.type=1;p.valid.SetInParent();p.avgPoolExcludePadding=True;convert(self.write(spec),self.root/'out','legacy_layers');data=np.random.default_rng(45).random((1,3,16,16),dtype=np.float32);padded=np.pad((data+np.array([1,2,3],dtype=np.float32).reshape(1,3,1,1)).swapaxes(2,3),((0,0),(0,0),(1,1),(1,1)));expected=sum(padded[:,:,y:y+16,x:x+16] for y in range(3) for x in range(3))/9;np.testing.assert_allclose(self.run_model(self.root/'out/legacy_layers.onnx',data),expected,rtol=1e-6,atol=1e-6)
    def test_image_grayscale_rectangular_whole_frame_and_float16_colorspace(self):
        for color in (10,40):
            spec=Model_pb2.Model();spec.specificationVersion=6
            for output,name,width,height in [(False,'image',7,5),(True,'rendered',42,15)]:f=(spec.description.output if output else spec.description.input).add();f.name=name;f.type.imageType.colorSpace=color;f.type.imageType.width=width;f.type.imageType.height=height
            network=spec.neuralNetwork;network.imageInputShapeMapping=1;l=network.layers.add();l.name='upsample';l.input.append('image');l.output.append('rendered');l.upsample.scalingFactor.extend([3,6]);report=convert(self.write(spec),self.root/'out','gray');data=np.random.default_rng(46).random((1,3,5,7),dtype=np.float32);actual=ort.InferenceSession(str(self.root/'out/gray.onnx')).run(None,{report['inputName']:data})[0];luma=np.sum(data*np.array([0.2126,0.7152,0.0722],dtype=np.float32).reshape(1,3,1,1),axis=1,keepdims=True);expected=np.repeat(np.repeat(np.repeat(luma,3,1),3,2),6,3);np.testing.assert_allclose(actual,expected,rtol=1e-6,atol=1e-7);self.assertTrue(report['appCompatible']);self.assertEqual(report['processingMode'],'image');self.assertEqual((report['inputWidth'],report['inputHeight'],report['outputWidth'],report['outputHeight']),(7,5,42,15));self.assertEqual(report['outputName'],'aidoku_normalized_output');self.assertFalse(report['inputHalfStep'])
    def test_multiarray_scale_above_four_and_small_blocks_are_retained(self):
        spec=nn_spec();spec.description.input[0].type.multiArrayType.shape[:]=[1,3,3,3];spec.description.output[0].type.multiArrayType.shape[:]=[1,3,15,15];spec.neuralNetwork.layers[-1].upsample.scalingFactor[:]=[5,5];report=convert(self.write(spec),self.root/'out','scale5',scale_override=5);self.assertTrue(report['appCompatible']);self.assertEqual(report['scale'],5);data=np.random.default_rng(47).random((1,3,3,3),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/scale5.onnx',data),np.repeat(np.repeat(data,5,2),5,3))
    def test_multiarray_custom_flat_three_and_five_dimensional_shapes_preserve_original_planar_buffers(self):
        data=np.random.default_rng(48).random((1,3,16,16),dtype=np.float32)
        for shape in ([768],[3,16,16],[1,1,3,16,16]):
            spec=mil_spec();spec.description.input[0].type.multiArrayType.shape[:]=shape;spec.description.output[0].type.multiArrayType.shape[:]=shape;function=spec.mlProgram.functions['main'];tensor(function.inputs[0].type,MIL_pb2.FLOAT32,[]);del function.inputs[0].type.tensorType.dimensions[:];tensor(function.inputs[0].type,MIL_pb2.FLOAT32,shape);block=function.block_specializations['CoreML5'];del block.operations[:];mil_const(block,'factor',0.75);mil_op(block,'mul','output',{'x':'input','y':'factor'},shape);report=convert(self.write(spec),self.root/'out','customshape');self.assertTrue(report['appCompatible']);self.assertEqual(report['inputShape'],[1,3,16,16]);self.assertEqual(report['outputShape'],[1,3,16,16]);np.testing.assert_array_equal(self.run_model(self.root/'out/customshape.onnx',data),data*0.75)
    def test_channel_vector_nn_scale_and_bias_broadcast_on_channels(self):
        spec=nn_spec();network=spec.neuralNetwork;del network.layers[:];l=network.layers.add();l.name='scale';l.input.append('input');l.output.append('scaled');l.scale.shapeScale.append(3);l.scale.scale.floatValue.extend([2,3,4]);l.scale.hasBias=True;l.scale.shapeBias.append(3);l.scale.bias.floatValue.extend([0.1,0.2,0.3]);l=network.layers.add();l.name='bias';l.input.append('scaled');l.output.append('output');l.bias.shape.append(3);l.bias.bias.floatValue.extend([0.3,0.2,0.1]);convert(self.write(spec),self.root/'out','scalechannels');data=np.random.default_rng(49).random((1,3,16,16),dtype=np.float32);expected=data*np.array([2,3,4],dtype=np.float32).reshape(1,3,1,1)+np.array([0.1,0.2,0.3],dtype=np.float32).reshape(1,3,1,1)+np.array([0.3,0.2,0.1],dtype=np.float32).reshape(1,3,1,1);np.testing.assert_array_equal(self.run_model(self.root/'out/scalechannels.onnx',data),expected)
    def test_image_pipeline_intermediate_uint8_buffers_are_materialized(self):
        spec=Model_pb2.Model();spec.specificationVersion=6
        def image(model,name,output):f=(model.description.output if output else model.description.input).add();f.name=name;f.type.imageType.colorSpace=20;f.type.imageType.width=7;f.type.imageType.height=5
        image(spec,'image',False);image(spec,'result',True)
        for source,result,offset in [('image','middle',0.7),('middle','result',2.4)]:
            child=spec.pipeline.models.add();child.specificationVersion=6;image(child,source,False);image(child,result,True);child.neuralNetwork.imageInputShapeMapping=1;l=child.neuralNetwork.layers.add();l.name='offset';l.input.append(source);l.output.append(result);l.add.alpha=offset
        report=convert(self.write(spec),self.root/'out','imagepipeline');data=np.random.default_rng(50).random((1,3,5,7),dtype=np.float32)*0.5;actual=ort.InferenceSession(str(self.root/'out/imagepipeline.onnx')).run(None,{report['inputName']:data})[0];np.testing.assert_array_equal(actual,(np.floor(data*255+np.float32(0.7))+np.float32(2.4))/255);self.assertTrue(report['appCompatible']);self.assertTrue(any(row['operator']=='image_pipeline_buffer' for row in report['graph']))
    def test_mil_topk_values_only_and_unsigned_indices_variants(self):
        for returns in (False,True):
            spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];mil_const(block,'k',5,MIL_pb2.INT32);mil_const(block,'axis',3,MIL_pb2.INT32);mil_const(block,'return_indices',returns,MIL_pb2.BOOL);mil_const(block,'sort',True,MIL_pb2.BOOL);op=mil_op(block,'topk','values',{'x':'input','k':'k','axis':'axis','return_indices':'return_indices','sort':'sort'},[1,3,16,5])
            if returns:mil_const(block,'index_dtype','uint16',MIL_pb2.STRING);op.inputs['output_indices_dtype'].arguments.add().name='index_dtype';out=op.outputs.add();out.name='indices';tensor(out.type,MIL_pb2.UINT16,[1,3,16,5])
            mil_op(block,'reduce_mean','output',{'x':'values'},[],MIL_pb2.FLOAT32);convert(self.write(spec),self.root/'out','topk',allow_general=True);data=np.random.default_rng(51).random((1,3,16,16),dtype=np.float32);expected=np.sort(data,axis=3)[...,-5:].mean();np.testing.assert_allclose(self.run_model(self.root/'out/topk.onnx',data),expected,rtol=1e-6,atol=1e-7)
    def test_nn_dynamic_slice_masks_reps_and_cumsum_inputs_override_parameters(self):
        spec=nn_spec();network=spec.neuralNetwork;del network.layers[:]
        def constant(name,value):l=network.layers.add();l.name=name;l.output.append(name);l.loadConstantND.shape.extend(np.shape(value));l.loadConstantND.data.floatValue.extend(np.asarray(value).flatten())
        for name,value in [('begin',[0,0,0,-1]),('end',[1,3,16,0]),('strides',[1,1,1,-1]),('bm',[0]*4),('em',[0,0,0,1]),('sq',[0]*4),('reps',[1]*4),('axis',[1])]:constant(name,value)
        l=network.layers.add();l.name='slice';l.input.extend(['input','begin','end','strides','bm','em','sq']);l.output.append('flipped');l.sliceDynamic.strides.extend([2]*4);l.sliceDynamic.endIds.extend([0]*4);l.sliceDynamic.squeezeMasks.extend([True]*4);l=network.layers.add();l.name='tile';l.input.extend(['flipped','reps']);l.output.append('tiled');l.tile.reps.extend([3]*4);l=network.layers.add();l.name='sum';l.input.extend(['tiled','axis']);l.output.append('output');l.cumSum.axis=3;convert(self.write(spec),self.root/'out','runtimevectors');data=np.random.default_rng(52).random((1,3,16,16),dtype=np.float32);np.testing.assert_allclose(self.run_model(self.root/'out/runtimevectors.onnx',data),np.cumsum(data[:,:,:,::-1],axis=1),rtol=1e-6,atol=1e-7)
    def test_nn_dynamic_convolution_weights_and_loop_with_only_new_values(self):
        spec=nn_spec();network=spec.neuralNetwork;del network.layers[:];weight=np.array([[1,0,0],[0,0.5,0],[0,0,0.25]],dtype=np.float32).reshape(3,3,1,1);l=network.layers.add();l.name='weights';l.output.append('weights');l.loadConstantND.shape.extend(weight.shape);l.loadConstantND.data.floatValue.extend(weight.flatten());l=network.layers.add();l.name='dynamicconv';l.input.extend(['input','weights']);l.output.append('filtered');p=l.convolution;p.kernelChannels=p.outputChannels=3;p.kernelSize.extend([1,1]);p.valid.SetInParent();loop=network.layers.add().loop;loop.maxLoopIterations=2;l=loop.bodyNetwork.layers.add();l.name='newvalue';l.input.append('filtered');l.output.append('inside');l.multiply.alpha=2;l=network.layers.add();l.name='copy';l.input.append('inside');l.output.append('output');l.copy.SetInParent();convert(self.write(spec),self.root/'out','dynamicconv');data=np.random.default_rng(53).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/dynamicconv.onnx',data),data*np.array([2,1,0.5],dtype=np.float32).reshape(1,3,1,1))
    def test_mil_gru_reset_after_and_bidirectional_lstm_outputs_match_numpy(self):
        rng=np.random.default_rng(54);data=rng.normal(size=(4,2,3)).astype(np.float32)*0.2;sigmoid=lambda x:1/(1+np.exp(-x))
        for kind in ('gru','lstm'):
            spec=mil_spec();block=spec.mlProgram.functions['main'].block_specializations['CoreML5'];del block.operations[:];hidden=2;directions=1 if kind=='gru' else 2;gates=3 if kind=='gru' else 4;initial=rng.normal(size=(2,hidden*directions)).astype(np.float32)*0.1;initial_c=rng.normal(size=(2,hidden*directions)).astype(np.float32)*0.1;mil_const(block,'initial',initial);mil_const(block,'initial_c',initial_c);mil_const(block,'sequence',True,MIL_pb2.BOOL);mil_const(block,'direction','reverse' if kind=='gru' else 'bidirectional',MIL_pb2.STRING);args={'x':'input','initial_h':'initial','output_sequence':'sequence','direction':'direction'};weights=[];recursion=[];biases=[];peepholes=[]
            for direction in range(directions):
                suffix='_back' if direction else '';w=rng.normal(size=(gates*hidden,3)).astype(np.float32)*0.2;r=rng.normal(size=(gates*hidden,hidden)).astype(np.float32)*0.1;b=rng.normal(size=(gates*hidden,)).astype(np.float32)*0.05;ph=rng.normal(size=(3*hidden,)).astype(np.float32)*0.05;weights.append(np.split(w,gates));recursion.append(np.split(r,gates));biases.append(np.split(b,gates));peepholes.append(np.split(ph,3))
                for key,value in [('weight_ih',w),('weight_hh',r),('bias',b)]+([('peephole',ph)] if kind=='lstm' else []):name=key+suffix;mil_const(block,name,value);args[name]=name
            if kind=='lstm':args['initial_c']='initial_c';mil_const(block,'clip',0.4);args['clip']='clip'
            op=mil_op(block,kind,'output',args,[4,2,hidden*directions]);out=op.outputs.add();out.name='hidden';tensor(out.type,MIL_pb2.FLOAT32,[2,hidden*directions]);names=['output','hidden'];shapes=[[4,2,hidden*directions],[2,hidden*directions]]
            if kind=='lstm':out=op.outputs.add();out.name='cell';tensor(out.type,MIL_pb2.FLOAT32,[2,hidden*directions]);names.append('cell');shapes.append([2,hidden*directions])
            graph=Graph();graph.shapes['input']=list(data.shape);lower_mil(graph,spec,self.write(spec));self.assertEqual(graph.errors,[]);actual=execute_graph(graph,names,{'input':data},shapes);sequences=[];states=[];cells=[]
            for direction in range(directions):
                state=initial[:,direction*hidden:(direction+1)*hidden].copy();cell=initial_c[:,direction*hidden:(direction+1)*hidden].copy();history=[];reverse=kind=='gru' or direction==1
                for x in data[::-1] if reverse else data:
                    affine=lambda gate:x@weights[direction][gate].T+state@recursion[direction][gate].T+biases[direction][gate]
                    if kind=='gru':reset=sigmoid(affine(0));update=sigmoid(affine(2));candidate=np.tanh(x@weights[direction][1].T+reset*(state@recursion[direction][1].T)+biases[direction][1]);state=(1-update)*candidate+update*state
                    else:i=sigmoid(affine(0)+peepholes[direction][0]*cell);f=sigmoid(affine(1)+peepholes[direction][1]*cell);g=np.tanh(affine(3));cell=np.clip(f*cell+i*g,-0.4,0.4);o=sigmoid(affine(2)+peepholes[direction][2]*cell);state=o*np.tanh(cell)
                    history.append(state.copy())
                sequences.append(np.stack(history[::-1] if direction else history));states.append(state);cells.append(cell)
            expected=[np.concatenate(sequences,axis=2),np.concatenate(states,axis=1)]+([np.concatenate(cells,axis=1)] if kind=='lstm' else [])
            for result,reference in zip(actual,expected):np.testing.assert_allclose(result,reference,rtol=4e-6,atol=1e-7)
    def test_nn_loop_mutable_values_conditional_continue_and_break(self):
        spec=nn_spec();del spec.neuralNetwork.layers[:];spec.description.output[0].name='value'
        def layer(network,kind,inputs=(),outputs=()):
            l=network.layers.add();l.name=kind;l.input.extend(inputs);l.output.extend(outputs);return getattr(l,kind)
        layer(spec.neuralNetwork,'copy',['input'],['value']).SetInParent();p=layer(spec.neuralNetwork,'loadConstantND',outputs=['count']);p.shape.extend([1]);p.data.floatValue.append(0);loop=layer(spec.neuralNetwork,'loop');loop.maxLoopIterations=10;loop.conditionVar='keep_going';layer(loop.conditionNetwork,'lessThan',['count'],['keep_going']).alpha=5;layer(loop.bodyNetwork,'add',['count'],['count']).alpha=1;layer(loop.bodyNetwork,'equal',['count'],['skip']).alpha=2;branch=layer(loop.bodyNetwork,'branch',['skip']);layer(branch.ifBranch,'loopContinue').SetInParent();layer(loop.bodyNetwork,'multiply',['value'],['value']).alpha=2;layer(loop.bodyNetwork,'equal',['count'],['stop']).alpha=3;branch=layer(loop.bodyNetwork,'branch',['stop']);layer(branch.ifBranch,'loopBreak').SetInParent();layer(loop.bodyNetwork,'add',['value'],['value']).alpha=1;convert(self.write(spec),self.root/'out','nn_loop');data=np.random.default_rng(36).random((1,3,16,16),dtype=np.float32);np.testing.assert_array_equal(self.run_model(self.root/'out/nn_loop.onnx',data),(data*2+1)*2)
if __name__=='__main__':unittest.main(verbosity=2)
