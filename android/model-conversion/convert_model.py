"""Portable Core ML NN/MIL -> ONNX conversion with explicit unsupported-node reports.

This is independent of convert_coreml.py, which reproduces the two bundled models.
No Apple inference/compiler API is used. Readable graph/weight inputs are required.
"""
from __future__ import annotations
import argparse, contextlib, hashlib, io, json, logging, math, struct, sys, tempfile, zipfile
from pathlib import Path
import numpy as np
import onnx
from onnx import helper as H, numpy_helper as NH, TensorProto as T
import onnxruntime as ort
with contextlib.redirect_stderr(io.StringIO()):
    from coremltools.proto import Model_pb2, MIL_pb2

class Unsupported(ValueError): pass
def require(condition, message):
    if not condition: raise Unsupported(message)
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def dims(tensor): return [int(d.constant.size) if d.HasField('constant') else None for d in tensor.dimensions]
def pairs_to_pads(p): return list(p[::2])+list(p[1::2])
DTYPES={'FLOAT16':'<f2','FLOAT32':'<f4','FLOAT64':'<f8','INT8':'i1','INT16':'<i2','INT32':'<i4','INT64':'<i8','UINT8':'u1','UINT16':'<u2','UINT32':'<u4','UINT64':'<u8','BOOL':'?'}

def unpack_bits(raw, bits, count, signed=False, bitorder='big'):
    require(bits in range(1,9),f'unsupported packed precision {bits}')
    bits_array=np.unpackbits(np.frombuffer(raw,dtype=np.uint8),bitorder=bitorder)
    require(len(bits_array)>=count*bits,'truncated packed weights')
    powers=np.arange(bits-1,-1,-1) if bitorder=='big' else np.arange(bits)
    values=(bits_array[:count*bits].reshape(count,bits)*(2**powers)).sum(1).astype(np.int64)
    if signed: values=np.where(values>=2**(bits-1),values-2**bits,values)
    return values

def nn_weights(value, shape, axis=0):
    count=math.prod(shape)
    if value.floatValue: result=np.asarray(value.floatValue,dtype=np.float32)
    elif value.float16Value: result=np.frombuffer(value.float16Value,dtype='<f2').astype(np.float32)
    elif value.rawValue or value.int8RawValue:
        q=value.quantization; bits=int(q.numberOfBits)
        if value.int8RawValue: result=np.frombuffer(value.int8RawValue,dtype=np.int8).astype(np.float32)
        else: result=unpack_bits(value.rawValue,bits,count).astype(np.float32)
        result=result.reshape(shape)
        kind=q.WhichOneof('QuantizationType')
        if kind=='lookupTableQuantization':
            lut=np.asarray(q.lookupTableQuantization.floatValue,dtype=np.float32);require(len(lut)==2**bits,'invalid quantization lookup table');result=lut[result.astype(np.int64)]
        elif kind=='linearQuantization':
            scales=np.asarray(q.linearQuantization.scale,dtype=np.float32);bias=np.asarray(q.linearQuantization.bias,dtype=np.float32)
            if value.int8RawValue and not len(bias):bias=np.zeros_like(scales)
            require(len(scales) in (1,shape[axis]) and len(bias)==len(scales),'invalid per-channel quantization parameters')
            broadcast=[1]*len(shape);broadcast[axis]=len(scales);result=result*scales.reshape(broadcast)+bias.reshape(broadcast)
        else: raise Unsupported('quantized weights have no supported quantization parameters')
    else: raise Unsupported('missing weight values')
    require(result.size==count,f'weight count {result.size} != shape {shape}')
    return np.asarray(result,dtype=np.float32).reshape(shape)

def mil_value(value, model_path):
    tensor=value.type.tensorType;shape=dims(tensor);require(all(x is not None for x in shape),'constant has unknown dimensions')
    kind=MIL_pb2.DataType.Name(tensor.dataType)
    if value.HasField('blobFileValue'):
        blob=value.blobFileValue;relative=blob.fileName.removeprefix('@model_path/')
        path=(model_path.parent/relative).resolve();require(path.is_relative_to(model_path.parent.resolve()),'weight blob path escapes model directory')
        with path.open('rb') as stream:
            stream.seek(blob.offset);header=stream.read(64);require(len(header)==64,'truncated weight record')
            magic,_,length,offset=struct.unpack_from('<IIQQ',header);require(magic==0xDEADBEEF,'invalid Core ML weight record')
            require(length<=1024**3 and offset+length<=path.stat().st_size,'weight record size exceeds file')
            stream.seek(offset);raw=stream.read(length)
        if kind in ('INT4','UINT4','UINT1','UINT2','UINT3','UINT6'): result=unpack_bits(raw,int(kind[-1]),math.prod(shape),kind=='INT4',bitorder='little')
        else: require(kind in DTYPES,f'unsupported blob type {kind}');result=np.frombuffer(raw,dtype=DTYPES[kind])
    else:
        t=value.immediateValue.tensor
        if kind=='STRING': return t.strings.values[0] if not shape else list(t.strings.values)
        if t.HasField('bytes'):
            if kind in ('INT4','UINT4','UINT1','UINT2','UINT3','UINT6'):result=unpack_bits(t.bytes.values,int(kind[-1]),math.prod(shape),kind=='INT4',bitorder='little')
            else:require(kind in DTYPES,f'unsupported immediate byte type {kind}');result=np.frombuffer(t.bytes.values,dtype=DTYPES[kind])
        else:
            field={'FLOAT16':'floats','FLOAT32':'floats','FLOAT64':'doubles','INT64':'longInts','UINT64':'longInts','BOOL':'bools'}.get(kind,'ints')
            require(kind in DTYPES,f'unsupported immediate type {kind}');result=np.asarray(getattr(t,field).values,dtype=DTYPES[kind])
    require(result.size==math.prod(shape),'constant size differs from declared shape')
    # Android's upscaling input is float32. Preserve stored weight values while promoting
    # FP16 arithmetic; the output report makes the intermediate precision difference explicit.
    return result.astype(np.float32 if np.issubdtype(result.dtype,np.floating) else result.dtype).reshape(shape)

def decompress_lut(indices,lut,vector_axis=None):
    require(lut.ndim==indices.ndim+2,'LUT rank must be indices rank + 2');vector=lut.shape[-1]
    if vector>1:
        require(vector_axis is not None,'vector LUT requires a vector axis');axis=int(vector_axis)%indices.ndim;indices=np.swapaxes(indices,-1,axis);lut=np.swapaxes(lut,indices.ndim-1,axis)
    for dim,size in enumerate(indices.shape):require(size%lut.shape[dim]==0,'invalid LUT block shape');lut=np.repeat(lut,size//lut.shape[dim],dim)
    require(indices.max(initial=0)<lut.shape[-2],'LUT index exceeds palette');dense=lut.reshape(-1,lut.shape[-2],vector)[np.arange(indices.size),indices.astype(np.int64).flatten()].reshape(list(indices.shape[:-1])+[indices.shape[-1]*vector])
    return np.swapaxes(dense,-1,axis) if vector>1 else dense

def decompress_shift_scale(data,scale,offset=None):
    def blocks(value):
        value=np.asarray(value);require(value.ndim in (0,data.ndim),'blockwise quantization rank mismatch')
        if value.ndim==0:return value
        for axis,size in enumerate(data.shape):require(size%value.shape[axis]==0,'invalid blockwise scale shape');value=np.repeat(value,size//value.shape[axis],axis)
        return value
    return (data.astype(np.float32)-blocks(0 if offset is None else offset))*blocks(scale)

class Graph:
    def __init__(self): self.nodes=[];self.initializers={};self.constants={};self.shapes={};self.types={};self.known={};self.counter=0;self.errors=[];self.inventory=[];self.reserved=set();self.node_names=set();self.fixed_lists=set()
    def const(self,value,name=None,dtype=None):
        name=name or self.unique('constant');array=np.asarray(value,dtype=dtype);self.constants[name]=array;self.shapes[name]=list(array.shape);self.initializers[name]=NH.from_array(array,name);kind=T.DataType.Name(self.initializers[name].data_type);self.types[name]={'FLOAT':'FLOAT32','DOUBLE':'FLOAT64'}.get(kind,kind);return name
    def unique(self,prefix):
        while True:
            self.counter+=1;name=f'{prefix}_{self.counter}'
            if name not in self.reserved and name not in self.shapes and name not in self.constants:self.reserved.add(name);return name
    def node(self,kind,inputs,outputs=None,name=None,**attrs):
        outputs=outputs or [self.unique(kind.lower())];node_name=name or outputs[0];node_name=self.unique(node_name+'_node') if node_name in self.node_names else node_name;self.node_names.add(node_name);self.nodes.append(H.make_node(kind,inputs,outputs,name=node_name,**attrs))
        if kind=='Cast':dtype=T.DataType.Name(attrs['to']);self.types[outputs[0]]={'FLOAT':'FLOAT32','DOUBLE':'FLOAT64'}.get(dtype,dtype)
        elif kind in ('Shape','Size','NonZero','ArgMax','ArgMin'):self.types.setdefault(outputs[0],'INT64')
        elif kind in ('Less','Equal','Greater','LessOrEqual','GreaterOrEqual','And','Or','Xor','Not'):self.types.setdefault(outputs[0],'BOOL')
        elif kind=='Where' and inputs[1] in self.types:self.types.setdefault(outputs[0],self.types[inputs[1]])
        elif inputs and inputs[0] in self.types:self.types.setdefault(outputs[0],self.types[inputs[0]])
        try:
            shape=self.shapes.get(inputs[0]);result=None
            if kind in ('Identity','Cast','Relu','LeakyRelu','Elu','PRelu','Sigmoid','Tanh','Clip','Abs','Sqrt','Floor','Ceil','Round','Softmax','BatchNormalization','Softplus','Softsign','HardSigmoid','Erf','Log','Exp','Reciprocal','InstanceNormalization','LRN','Sign','Sin','Cos','Tan','Asin','Acos','Atan','Sinh','Cosh','Asinh','Acosh','Atanh','Not'):result=shape
            elif kind in ('Add','Sub','Mul','Div','Max','Min','Pow','Less','Greater','Equal','LessOrEqual','GreaterOrEqual','And','Or','Xor'):result=list(np.broadcast_shapes(*[tuple(self.shapes[x]) for x in inputs]))
            elif kind=='Where':result=list(np.broadcast_shapes(*[tuple(self.shapes[x]) for x in inputs]))
            elif kind=='Pad' and shape is not None:
                pads=np.asarray(self.constants.get(inputs[1],self.known.get(inputs[1]))).astype(np.int64);result=[size+int(pads[i])+int(pads[i+len(shape)]) for i,size in enumerate(shape)]
            elif kind.startswith('Reduce') and shape is not None:
                axes=list(attrs.get('axes',self.constants[inputs[1]].flatten().astype(int).tolist() if len(inputs)>1 else range(len(shape))));axes=[axis%len(shape) for axis in axes];result=[1 if axis in axes else size for axis,size in enumerate(shape)] if attrs.get('keepdims',1) else [size for axis,size in enumerate(shape) if axis not in axes]
            elif kind in ('Conv','ConvTranspose') and shape:
                weight=self.shapes[inputs[1]];rank=len(shape)-2;stride=attrs.get('strides',[1]*rank);dilation=attrs.get('dilations',[1]*rank);pads=attrs.get('pads',[0]*(2*rank));same=attrs.get('auto_pad','').startswith('SAME');spatial=[]
                for i,size in enumerate(shape[2:]):
                    effective=(weight[i+2]-1)*dilation[i]+1
                    spatial.append(math.ceil(size/stride[i]) if same else (size-1)*stride[i]+effective-pads[i]-pads[i+rank] if kind=='ConvTranspose' else (size+pads[i]+pads[i+rank]-effective)//stride[i]+1)
                result=shape[:1]+[weight[0] if kind=='Conv' else weight[1]*attrs.get('group',1)]+attrs.get('output_shape',spatial)
            elif kind in ('MaxPool','AveragePool','LpPool') and shape:
                kernels=attrs['kernel_shape'];strides=attrs.get('strides',[1]*len(kernels));pads=attrs.get('pads',[0]*(2*len(kernels)));same=attrs.get('auto_pad','').startswith('SAME');spatial=[]
                for i,size in enumerate(shape[2:]):
                    count=math.ceil(size/strides[i]) if same else (math.ceil if attrs.get('ceil_mode') else math.floor)((size+pads[i]+pads[i+len(kernels)]-kernels[i])/strides[i])+1
                    if attrs.get('ceil_mode') and (count-1)*strides[i]>=size+pads[i]:count-=1
                    spatial.append(count)
                result=shape[:2]+spatial
            elif kind in ('GlobalMaxPool','GlobalAveragePool') and shape:result=shape[:2]+[1]*(len(shape)-2)
            elif kind=='Transpose' and shape:result=[shape[i] for i in attrs.get('perm',list(reversed(range(len(shape)))))]
            elif kind=='Gather' and shape:
                axis=attrs.get('axis',0)%len(shape);result=shape[:axis]+self.shapes[inputs[1]]+shape[axis+1:]
            elif kind=='Concat':result=list(shape);axis=attrs['axis'];result[axis]=sum(self.shapes[x][axis] for x in inputs)
            elif kind=='Resize' and shape:result=self.constants[inputs[3]].astype(np.int64).tolist() if len(inputs)>3 and inputs[3] else [int(size*scale) for size,scale in zip(shape,self.constants[inputs[2]])]
            elif kind=='Tile' and shape:result=[size*int(repeats) for size,repeats in zip(shape,self.constants.get(inputs[1],self.known.get(inputs[1])))]
            elif kind=='Reshape' and (inputs[1] in self.constants or inputs[1] in self.known):
                result=np.asarray(self.constants.get(inputs[1],self.known.get(inputs[1]))).astype(np.int64).tolist();result=[shape[i] if x==0 else x for i,x in enumerate(result)]
                if -1 in result:result[result.index(-1)]=math.prod(shape)//-math.prod(result)
            elif kind=='Shape' and shape:
                result=[len(shape)]
                if all(x is not None for x in shape):self.known[outputs[0]]=np.asarray(shape,dtype=np.int64)
            elif kind=='Squeeze' and shape:
                axes=self.constants[inputs[1]].flatten().astype(int).tolist() if len(inputs)>1 else [i for i,size in enumerate(shape) if size==1];result=[size for i,size in enumerate(shape) if i not in [a%len(shape) for a in axes]]
            elif kind=='Unsqueeze' and shape is not None:
                axes=self.constants[inputs[1]].flatten().astype(int).tolist();rank=len(shape)+len(axes);result=list(shape)
                for axis in sorted(a%rank for a in axes):result.insert(axis,1)
            elif kind in ('DepthToSpace','SpaceToDepth') and shape:
                n,c,hh,ww=shape;r=attrs['blocksize'];result=[n,c//(r*r),hh*r,ww*r] if kind=='DepthToSpace' else [n,c*r*r,hh//r,ww//r]
            if result is not None:self.shapes[outputs[0]]=list(result)
        except (KeyError,TypeError,ValueError,ZeroDivisionError):pass
        try:
            values=[self.constants.get(value,self.known.get(value)) for value in inputs];known=all(value is not None for value in values)
            if known and kind in ('Add','Sub','Mul','Div','Max','Min','Less','Equal','Greater','Abs','Floor','Cast','Identity','Where','Gather','Concat','Slice','Reshape','Squeeze','Unsqueeze','Transpose'):
                if kind=='Cast':value=values[0].astype({T.FLOAT:np.float32,T.DOUBLE:np.float64,T.FLOAT16:np.float16,T.INT64:np.int64,T.INT32:np.int32,T.INT16:np.int16,T.INT8:np.int8,T.UINT64:np.uint64,T.UINT32:np.uint32,T.UINT16:np.uint16,T.UINT8:np.uint8,T.BOOL:np.bool_}[attrs['to']])
                elif kind=='Identity':value=values[0]
                elif kind=='Where':value=np.where(*values)
                elif kind=='Gather':value=np.take(values[0],values[1],axis=attrs.get('axis',0))
                elif kind=='Concat':value=np.concatenate(values,axis=attrs['axis'])
                elif kind=='Slice':
                    slices=[slice(None)]*values[0].ndim;starts,ends=values[1:3];axes=values[3] if len(values)>3 else range(len(starts));steps=values[4] if len(values)>4 else [1]*len(starts)
                    for start,end,axis,step in zip(starts,ends,axes,steps):slices[int(axis)]=slice(int(start),None if int(end)==np.iinfo(np.int64).min and int(step)<0 or int(end)==np.iinfo(np.int64).max else int(end),int(step))
                    value=values[0][tuple(slices)]
                elif kind=='Reshape':value=values[0].reshape([values[0].shape[i] if int(size)==0 else int(size) for i,size in enumerate(values[1])])
                elif kind=='Squeeze':value=np.squeeze(values[0],tuple(values[1].astype(int)) if len(values)>1 else None)
                elif kind=='Unsqueeze':value=np.expand_dims(values[0],tuple(values[1].astype(int)))
                elif kind=='Transpose':value=np.transpose(values[0],attrs.get('perm'))
                elif kind in ('Abs','Floor'):value={'Abs':np.abs,'Floor':np.floor}[kind](values[0])
                else:value={'Add':np.add,'Sub':np.subtract,'Mul':np.multiply,'Div':np.divide,'Max':np.maximum,'Min':np.minimum,'Less':np.less,'Equal':np.equal,'Greater':np.greater}[kind](*values)
                self.known[outputs[0]]=np.asarray(value)
            if kind=='Slice' and shape and all(value is not None for value in values[1:]):
                result=list(shape);starts,ends=values[1:3];axes=values[3] if len(values)>3 else range(len(starts));steps=values[4] if len(values)>4 else [1]*len(starts)
                for start,end,axis,step in zip(starts,ends,axes,steps):axis=int(axis)%len(shape);result[axis]=len(range(*slice(int(start),int(end),int(step)).indices(shape[axis])))
                self.shapes[outputs[0]]=result
        except (KeyError,TypeError,ValueError,IndexError,ZeroDivisionError):pass
        return outputs[0]
    def binary(self,kind,left,right,outputs=None): return self.node(kind,[left,right],outputs)
    def val(self,name): require(name in self.constants or name in self.known,f'dynamic parameter {name} requires unsupported lowering');return self.constants.get(name,self.known.get(name))
    def scalar(self,name): value=self.val(name);return value.item() if isinstance(value,np.ndarray) and value.size==1 else value
    def initializer(self,name):
        if name in self.constants and name not in self.initializers:
            require(not isinstance(self.constants[name],str),'string used as numeric tensor');self.const(self.constants[name],name)
        return name
    def resize(self,x,outputs,scales=None,sizes=None,mode='nearest',coordinates='asymmetric'):
        args=[x,'',self.const(scales,dtype=np.float32) if scales is not None else '',self.const(sizes,dtype=np.int64) if sizes is not None else '']
        self.node('Resize',args,outputs,mode=mode,coordinate_transformation_mode=coordinates,**({'nearest_mode':'floor'} if mode=='nearest' else {}))
    def normalize(self,x,outputs,axes,epsilon,gamma=None,beta=None):
        mean=self.node('ReduceMean',[x],axes=list(axes),keepdims=1);centered=self.binary('Sub',x,mean)
        variance=self.node('ReduceMean',[self.binary('Mul',centered,centered)],axes=list(axes),keepdims=1)
        result=self.binary('Div',centered,self.node('Sqrt',[self.binary('Add',variance,self.const(epsilon,dtype=np.float32))]))
        if gamma: result=self.binary('Mul',result,gamma)
        if beta: result=self.binary('Add',result,beta)
        self.node('Identity',[result],outputs)
    def sampled_resize(self,x,outputs,sizes,mode):
        current=x;shape=self.shapes[x]
        for axis,target in zip((2,3),sizes[-2:]):
            source=shape[axis]
            if mode=='OFFSET_CORNERS':positions=(np.arange(target,dtype=np.float32)+0.5)*max(1,source-1)/target
            elif mode=='ALIGN_CORNERS':positions=np.asarray([(source-1)/2],dtype=np.float32) if target==1 else np.linspace(0,source-1,target,dtype=np.float32)
            else:raise Unsupported(f'unknown explicit sampling mode {mode}')
            positions=np.clip(positions,0,source-1);low=np.floor(positions).astype(np.int64);high=np.minimum(low+1,source-1);weight_shape=[1]*4;weight_shape[axis]=target;weights=self.const((positions-low).astype(np.float32).reshape(weight_shape));left=self.node('Gather',[current,self.const(low)],axis=axis);right=self.node('Gather',[current,self.const(high)],axis=axis);current=self.binary('Add',left,self.binary('Mul',self.binary('Sub',right,left),weights))
        self.node('Identity',[current],outputs);self.shapes[outputs[0]]=list(sizes)
    def runtime_sampled_resize(self,x,sizes,outputs,sampling='DEFAULT',count=2,nearest=False):
        shape=self.shapes.get(x);require(shape and 0<count<=len(shape),'sampled resize requires known rank');current=x;rank=len(shape)
        for axis in range(rank-count,rank):
            source=self.node('Cast',[self.node('Gather',[self.node('Shape',[current]),self.const(axis,dtype=np.int64)],axis=0)],to=T.FLOAT);target=self.node('Gather',[sizes,self.const(axis,dtype=np.int64)],axis=0);target_float=self.node('Cast',[target],to=T.FLOAT);points=self.node('Cast',[self.node('Range',[self.const(0,dtype=np.int64),target,self.const(1,dtype=np.int64)])],to=T.FLOAT);span=self.binary('Sub',source,self.const(1,dtype=np.float32))
            if sampling in ('ALIGN_CORNERS','STRICT_ALIGN_CORNERS'):
                positions=self.binary('Mul',span,self.binary('Div',points,self.binary('Max',self.binary('Sub',target_float,self.const(1,dtype=np.float32)),self.const(1,dtype=np.float32))))
                if sampling=='ALIGN_CORNERS':positions=self.node('Where',[self.node('Equal',[target,self.const(1,dtype=np.int64)]),self.binary('Mul',span,self.const(0.5,dtype=np.float32)),positions])
            elif sampling=='OFFSET_CORNERS':positions=self.binary('Mul',self.binary('Add',points,self.const(0.5,dtype=np.float32)),self.binary('Div',self.binary('Max',span,self.const(1,dtype=np.float32)),target_float))
            else:
                require(sampling in ('DEFAULT','UNALIGN_CORNERS'),'unknown sampled resize mode');positions=self.binary('Mul',self.binary('Add',points,self.const(0.5 if sampling=='UNALIGN_CORNERS' else 0,dtype=np.float32)),self.binary('Div',source,target_float))
                if sampling=='UNALIGN_CORNERS':positions=self.binary('Sub',positions,self.const(0.5,dtype=np.float32))
            positions=self.binary('Min',self.binary('Max',positions,self.const(0,dtype=np.float32)),span);floor=self.node('Floor',[positions]);low=self.node('Cast',[floor],to=T.INT64)
            if nearest:current=self.node('Gather',[current,low],axis=axis);continue
            high=self.node('Cast',[self.binary('Min',self.binary('Add',floor,self.const(1,dtype=np.float32)),span)],to=T.INT64);weights_shape=self.node('Concat',[self.const([1]*axis,dtype=np.int64),self.node('Reshape',[target,self.const([1],dtype=np.int64)]),self.const([1]*(rank-axis-1),dtype=np.int64)],axis=0);weights=self.node('Reshape',[self.binary('Sub',positions,floor),weights_shape]);left=self.node('Gather',[current,low],axis=axis);right=self.node('Gather',[current,high],axis=axis);current=self.binary('Add',left,self.binary('Mul',self.binary('Sub',right,left),weights))
        self.node('Identity',[current],outputs)
    def gelu(self,x,outputs,mode='EXACT'):
        if mode=='SIGMOID_APPROXIMATION':result=self.binary('Mul',x,self.node('Sigmoid',[self.binary('Mul',x,self.const(1.702,dtype=np.float32))]))
        else:
            if mode=='EXACT':activation=self.node('Erf',[self.binary('Div',x,self.const(math.sqrt(2),dtype=np.float32))])
            elif mode=='TANH_APPROXIMATION':cube=self.binary('Mul',self.binary('Mul',x,x),x);inner=self.binary('Add',x,self.binary('Mul',cube,self.const(0.044715,dtype=np.float32)));activation=self.node('Tanh',[self.binary('Mul',inner,self.const(math.sqrt(2/math.pi),dtype=np.float32))])
            else:raise Unsupported(f'unknown GELU mode {mode}')
            result=self.binary('Mul',self.binary('Mul',x,self.const(0.5,dtype=np.float32)),self.binary('Add',activation,self.const(1,dtype=np.float32)))
        self.node('Identity',[result],outputs)
    def resample(self,x,coordinates,outputs,sampling='bilinear',padding='constant',padding_value=0,coordinates_mode='unnormalized',align_corners=True):
        shape=self.shapes.get(x);require(shape and len(shape)==4 and all(shape),'resampling requires fixed NCHW input');require(sampling in ('bilinear','nearest') and padding in ('constant','border','reflection','symmetric'),'invalid resampling mode');require(coordinates_mode in ('unnormalized','normalized_minus_one_to_one','normalized_zero_to_one'),'invalid resampling coordinates mode');sizes=np.array([shape[3],shape[2]],dtype=np.float32);pixel=self.node('Cast',[coordinates],to=T.FLOAT)
        if coordinates_mode!='unnormalized':
            normalized=self.binary('Mul',self.binary('Add',pixel,self.const(1,dtype=np.float32)),self.const(0.5,dtype=np.float32)) if coordinates_mode=='normalized_minus_one_to_one' else pixel
            pixel=self.binary('Mul',normalized,self.const(sizes-1 if align_corners else sizes))
            if not align_corners:pixel=self.binary('Sub',pixel,self.const(0.5,dtype=np.float32))
        if padding in ('reflection','symmetric'):
            span=np.maximum(sizes-1,1) if padding=='reflection' else sizes;shifted=pixel if padding=='reflection' else self.binary('Add',pixel,self.const(0.5,dtype=np.float32));period=self.const(2*span);remainder=self.binary('Sub',shifted,self.binary('Mul',period,self.node('Floor',[self.binary('Div',shifted,period)])));reflected=self.binary('Sub',self.const(span if padding=='reflection' else sizes-0.5),self.node('Abs',[self.binary('Sub',remainder,self.const(span))]));pixel=self.binary('Min',self.binary('Max',reflected,self.const(0,dtype=np.float32)),self.const(sizes-1));padding='border'
        grid=self.binary('Sub',self.binary('Div',self.binary('Add',self.binary('Mul',pixel,self.const(2,dtype=np.float32)),self.const(1,dtype=np.float32)),self.const(sizes)),self.const(1,dtype=np.float32));input=x
        if padding=='constant' and padding_value!=0:input=self.binary('Sub',input,self.const(padding_value,dtype=np.float32))
        result=self.node('GridSample',[input,grid],mode=sampling,padding_mode='zeros' if padding=='constant' else 'border',align_corners=0)
        if padding=='constant' and padding_value!=0:result=self.binary('Add',result,self.const(padding_value,dtype=np.float32))
        self.node('Identity',[result],outputs)
    def spatial_input(self,x,dimensions=2):
        shape=self.shapes.get(x);require(shape and len(shape)>=dimensions+1 and all(shape),'spatial operation requires fixed channel/spatial dimensions');prefix=shape[:-dimensions-1]
        if len(shape)==dimensions+2:return x,None
        folded=self.node('Reshape',[x,self.const([math.prod(prefix)]+shape[-dimensions-1:],dtype=np.int64)]);return folded,prefix
    def spatial_output(self,result,prefix,outputs,dimensions=2):
        if prefix is None:self.node('Identity',[result],outputs)
        else:
            tail=self.node('Slice',[self.node('Shape',[result]),self.const([1],dtype=np.int64),self.const([dimensions+2],dtype=np.int64)]);target=self.node('Concat',[self.const(prefix,dtype=np.int64),tail],axis=0);self.node('Reshape',[result,target],outputs)
    def crop_resize(self,x,roi,outputs,height,width,normalized=False,scale=1,box_mode='CORNERS_HEIGHT_FIRST',sampling='DEFAULT'):
        shape=self.shapes.get(x);rs=self.shapes.get(roi);require(shape and len(shape)==4 and all(shape) and rs and len(rs)==5 and rs[1]==rs[3]==rs[4]==1 and rs[2] in (4,5) and rs[0],'crop resize requires fixed NCHW and N,1,4/5,1,1 ROI');require(height>0 and width>0,'crop target dimensions must be positive');require(box_mode in ('CORNERS_HEIGHT_FIRST','CORNERS_WIDTH_FIRST','CENTER_SIZE_HEIGHT_FIRST','CENTER_SIZE_WIDTH_FIRST'),'unknown ROI coordinate order');require(sampling in ('DEFAULT','STRICT_ALIGN_CORNERS','ALIGN_CORNERS','OFFSET_CORNERS','UNALIGN_CORNERS'),'unknown ROI sampling');boxes=self.node('Reshape',[roi,self.const([rs[0],rs[2]],dtype=np.int64)]);batch,channels,hh,ww=shape;patches=[]
        def point(start,end,target):
            span=self.binary('Sub',end,start);size=self.binary('Add',span,self.const(1,dtype=np.float32));indices=self.const(np.arange(target,dtype=np.float32))
            if sampling in ('STRICT_ALIGN_CORNERS','ALIGN_CORNERS'):position=self.binary('Mul',span,self.const(np.asarray([0.5] if sampling=='ALIGN_CORNERS' and target==1 else np.arange(target,dtype=np.float32)/max(target-1,1),dtype=np.float32)))
            elif sampling=='OFFSET_CORNERS':position=self.binary('Mul',self.binary('Max',span,self.const(1,dtype=np.float32)),self.const((np.arange(target,dtype=np.float32)+0.5)/target))
            else:
                position=self.binary('Mul',size,self.const(np.arange(target,dtype=np.float32)/target if sampling=='DEFAULT' else (np.arange(target,dtype=np.float32)+0.5)/target))
                if sampling=='UNALIGN_CORNERS':position=self.binary('Sub',position,self.const(0.5,dtype=np.float32))
            return self.binary('Add',start,self.binary('Min',self.binary('Max',position,self.const(0,dtype=np.float32)),span))
        for index in range(rs[0]):
            row=self.node('Gather',[boxes,self.const(index,dtype=np.int64)],axis=0);coords=self.node('Slice',[row,self.const([rs[2]-4],dtype=np.int64),self.const([rs[2]],dtype=np.int64)])
            if box_mode.endswith('WIDTH_FIRST'):coords=self.node('Gather',[coords,self.const([1,0,3,2],dtype=np.int64)],axis=0)
            if normalized:coords=self.binary('Mul',coords,self.const([hh-1,ww-1,hh-1,ww-1],dtype=np.float32))
            coords=self.binary('Mul',coords,self.const(scale,dtype=np.float32));start=self.node('Slice',[coords,self.const([0],dtype=np.int64),self.const([2],dtype=np.int64)]);end=self.node('Slice',[coords,self.const([2],dtype=np.int64),self.const([4],dtype=np.int64)])
            if box_mode.startswith('CENTER_SIZE'):half=self.binary('Mul',end,self.const(0.5,dtype=np.float32));end=self.binary('Add',start,half);start=self.binary('Sub',start,half)
            sy,sx=[self.node('Gather',[start,self.const(axis,dtype=np.int64)],axis=0) for axis in (0,1)];ey,ex=[self.node('Gather',[end,self.const(axis,dtype=np.int64)],axis=0) for axis in (0,1)];ys=point(sy,ey,height);xs=point(sx,ex,width);gx=self.node('Expand',[self.node('Reshape',[xs,self.const([1,1,width,1],dtype=np.int64)]),self.const([1,height,width,1],dtype=np.int64)]);gy=self.node('Expand',[self.node('Reshape',[ys,self.const([1,height,1,1],dtype=np.int64)]),self.const([1,height,width,1],dtype=np.int64)]);grid=self.node('Concat',[gx,gy],axis=3);image=x;outbatch=batch
            if rs[2]==5:
                bid=self.node('Cast',[self.node('Gather',[row,self.const([0],dtype=np.int64)],axis=0)],to=T.INT64);image=self.node('Gather',[x,bid],axis=0);self.shapes[image]=[1,channels,hh,ww];outbatch=1
            elif batch!=1:grid=self.node('Expand',[grid,self.const([batch,height,width,2],dtype=np.int64)])
            patch=self.unique('crop');self.resample(image,grid,[patch],padding='border');patches.append(self.node('Unsqueeze',[patch,self.const([0],dtype=np.int64)]))
        self.node('Concat',patches,outputs,axis=0);self.shapes[outputs[0]]=[rs[0],outbatch,channels,height,width]
    def nms(self,boxes,scores,outputs,iou,threshold,limit,per_class=False,integer_outputs=False):
        bs=self.shapes.get(boxes);ss=self.shapes.get(scores);require(bs and ss and len(bs)==len(ss)==3 and bs[:2]==ss[:2] and bs[2]==4 and all(bs+ss),'NMS requires fixed B,N,4 boxes and B,N,C scores');batch,count,_=bs;classes=ss[2];limit=self.node('Reshape',[self.node('Cast',[limit],to=T.INT64),self.const([],dtype=np.int64)]);iou=self.node('Reshape',[iou,self.const([],dtype=np.int64)]);threshold=self.node('Reshape',[threshold,self.const([],dtype=np.int64)]);collected=[[],[],[],[]]
        for b in range(batch):
            coordinates=self.node('Gather',[boxes,self.const(b,dtype=np.int64)],axis=0);values=self.node('Gather',[scores,self.const(b,dtype=np.int64)],axis=0);priority=self.node('ReduceMax',[values],axes=[1],keepdims=0);labels=self.node('ArgMax',[values],axis=1,keepdims=0);center=self.node('Slice',[coordinates,self.const([0],dtype=np.int64),self.const([2],dtype=np.int64),self.const([1],dtype=np.int64)]);size=self.node('Slice',[coordinates,self.const([2],dtype=np.int64),self.const([4],dtype=np.int64),self.const([1],dtype=np.int64)]);half=self.binary('Mul',size,self.const(0.5,dtype=np.float32));lower=self.binary('Sub',center,half);upper=self.binary('Add',center,half);area=self.node('ReduceProd',[size],axes=[1],keepdims=0);child=Graph();child.reserved=set(self.reserved)|set(self.shapes)|set(self.constants);iteration=child.unique('step');cond=child.unique('condition');current=child.unique('scores');idx=child.node('ArgMax',[current],axis=0,keepdims=0);selected_lower=child.node('Gather',[lower,idx],axis=0);selected_upper=child.node('Gather',[upper,idx],axis=0);intersection=child.binary('Max',child.binary('Sub',child.binary('Min',upper,selected_upper),child.binary('Max',lower,selected_lower)),child.const(0,dtype=np.float32));intersection=child.node('ReduceProd',[intersection],axes=[1],keepdims=0);union=child.binary('Sub',child.binary('Add',area,child.node('Gather',[area,idx],axis=0)),intersection);overlap=child.binary('Div',intersection,child.binary('Max',union,child.const(np.finfo(np.float32).tiny,dtype=np.float32)));suppressed=child.node('Greater',[overlap,iou])
            if per_class:suppressed=child.node('And',[suppressed,child.node('Equal',[labels,child.node('Gather',[labels,idx],axis=0)])])
            suppressed=child.node('Or',[suppressed,child.node('Equal',[child.const(np.arange(count),dtype=np.int64),idx])]);next_scores=child.node('Where',[suppressed,child.const(-np.inf,dtype=np.float32),current]);remaining=child.node('ReduceMax',[next_scores],axes=[0],keepdims=0);next_cond=child.node('And',[child.node('GreaterOrEqual',[remaining,threshold]),child.node('Greater',[remaining,child.const(-np.inf,dtype=np.float32)])]);body=H.make_graph(child.nodes,'greedy_nms',[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(cond,T.BOOL,[]),H.make_tensor_value_info(current,T.FLOAT,[count])],[H.make_tensor_value_info(next_cond,T.BOOL,[]),H.make_tensor_value_info(next_scores,T.FLOAT,[count]),H.make_tensor_value_info(idx,T.INT64,[])],list(child.initializers.values()));scan=self.unique('selected');self.node('Loop',[limit,self.const(True),priority],[self.unique('remaining'),scan],body=body);length=self.node('Size',[scan]);padding=self.binary('Sub',limit,length);padded=self.node('Pad',[scan,self.node('Concat',[self.const([0],dtype=np.int64),self.node('Reshape',[padding,self.const([1],dtype=np.int64)])],axis=0),self.const(-1,dtype=np.int64)]);safe=self.node('Where',[self.node('Less',[padded,self.const(0,dtype=np.int64)]),self.const(count,dtype=np.int64),padded]);padded_boxes=self.node('Concat',[coordinates,self.const(np.zeros((1,4),dtype=np.float32))],axis=0);padded_scores=self.node('Concat',[values,self.const(np.zeros((1,classes),dtype=np.float32))],axis=0);results=[self.node('Gather',[padded_boxes,safe],axis=0),self.node('Gather',[padded_scores,safe],axis=0),self.node('Cast',[padded],to=T.INT32 if integer_outputs else T.FLOAT),self.node('Cast',[length],to=T.INT32 if integer_outputs else T.FLOAT)]
            for target,value in zip(collected,results):target.append(self.node('Unsqueeze',[value,self.const([0],dtype=np.int64)]))
        for values,out in zip(collected,outputs):self.node('Concat',values,[out],axis=0)
    def scatter(self,data,indices,updates,outputs,kind='scatter_nd',axis=0,mode='add'):
        require(mode in ('update','add','sub','mul','div','max','min'),'unknown scatter reduction');shape=self.shapes.get(data);idxshape=self.shapes.get(indices);upshape=self.shapes.get(updates);require(shape and idxshape and upshape and all(shape+idxshape+upshape),'scatter currently requires fixed tensor shapes');rank=len(shape);indices=self.node('Cast',[indices],to=T.INT64)
        if kind=='scatter':
            axis%=rank;perm=[axis]+[i for i in range(rank) if i!=axis];data=self.node('Transpose',[data],perm=perm);updates=self.node('Transpose',[updates],perm=perm);shape=[shape[i] for i in perm];indices=self.node('Reshape',[indices,self.const([-1,1],dtype=np.int64)]);idxshape=[math.prod(idxshape),1];upshape=[upshape[i] for i in perm]
        elif kind=='scatter_along_axis':
            axis%=rank;grid=np.indices(upshape,dtype=np.int64);parts=[]
            for i in range(rank):parts.append(self.node('Unsqueeze',[indices if i==axis else self.const(grid[i]),self.const([-1],dtype=np.int64)]))
            indices=self.node('Concat',parts,axis=-1);idxshape=upshape+[rank]
        depth=idxshape[-1];count=math.prod(idxshape[:-1]);require(0<depth<=len(shape),'scatter index depth exceeds tensor rank');flat_indices=self.node('Reshape',[indices,self.const([count,depth],dtype=np.int64)]);flat_updates=self.node('Reshape',[updates,self.const([count]+shape[depth:],dtype=np.int64)]);child=Graph();child.reserved=set(self.reserved)|set(self.shapes)|set(self.constants);iteration=child.unique('index');cond=child.unique('condition');current=child.unique('data');dtype=tensor_dtype(self.types.get(data,self.types.get(outputs[0],'FLOAT32')));infos=[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(cond,T.BOOL,[]),H.make_tensor_value_info(current,dtype,shape)]
        index=child.node('Gather',[flat_indices,iteration],axis=0);value=child.node('Gather',[flat_updates,iteration],axis=0)
        if mode!='update':old=child.node('GatherND',[current,index]);value=child.binary({'add':'Add','sub':'Sub','mul':'Mul','div':'Div','max':'Max','min':'Min'}[mode],old,value)
        next_data=child.node('ScatterND',[current,child.node('Unsqueeze',[index,child.const([0],dtype=np.int64)]),child.node('Unsqueeze',[value,child.const([0],dtype=np.int64)])]);outinfos=[H.make_tensor_value_info(cond,T.BOOL,[]),H.make_tensor_value_info(next_data,dtype,shape)];body=H.make_graph(child.nodes,'ordered_scatter',infos,outinfos,list(child.initializers.values()));result=self.node('Loop',[self.const(count,dtype=np.int64),self.const(True),data],body=body)
        if kind=='scatter':self.node('Transpose',[result],outputs,perm=np.argsort(perm).tolist())
        else:self.node('Identity',[result],outputs)
    def slice(self,x,begin,end,outputs,strides=None,begin_mask=None,end_mask=None,squeeze=None,by_size=False):
        shape=self.shapes.get(x);require(shape is not None,'slice requires known input rank');rank=len(shape)
        def vector(value,default,dtype):
            if isinstance(value,str):return self.node('Cast',[value],to=T.BOOL if dtype==bool else T.INT64)
            array=np.asarray(default if value is None else value,dtype=dtype);require(array.shape==(rank,),'slice rank/mask/stride mismatch');return self.const(array)
        steps=vector(strides,[1]*rank,np.int64);bm=vector(begin_mask,[False]*rank,bool);em=vector(end_mask,[False]*rank,bool);sq=vector(squeeze,[False]*rank,bool);known_steps=self.constants.get(steps,self.known.get(steps));require(known_steps is None or np.all(known_steps!=0),'slice strides cannot be zero');starts=self.node('Cast',[begin],to=T.INT64);ends=self.node('Cast',[end],to=T.INT64);dimensions=self.node('Shape',[x]);normalized=self.node('Where',[self.node('Less',[starts,self.const(0,dtype=np.int64)]),self.binary('Add',starts,dimensions),starts])
        if by_size:starts=normalized;ends=self.node('Where',[self.node('Less',[ends,self.const(0,dtype=np.int64)]),self.const(np.iinfo(np.int64).max,dtype=np.int64),self.binary('Add',starts,ends)])
        else:
            negative=self.node('Less',[steps,self.const(0,dtype=np.int64)]);starts=self.node('Where',[bm,self.node('Where',[negative,self.const(-1,dtype=np.int64),self.const(0,dtype=np.int64)]),starts]);ends=self.node('Where',[em,self.node('Where',[negative,self.const(np.iinfo(np.int64).min,dtype=np.int64),self.const(np.iinfo(np.int64).max,dtype=np.int64)]),ends]);starts=self.node('Where',[sq,normalized,starts]);ends=self.node('Where',[sq,self.binary('Add',normalized,self.const(1,dtype=np.int64)),ends]);steps=self.node('Where',[sq,self.const(1,dtype=np.int64),steps])
        sliced=self.node('Slice',[x,starts,ends,self.const(np.arange(rank),dtype=np.int64),steps]);known_squeeze=self.constants.get(sq,self.known.get(sq))
        if known_squeeze is None or np.any(known_squeeze):self.node('Squeeze',[sliced,self.node('Squeeze',[self.node('NonZero',[sq]),self.const([0],dtype=np.int64)])],outputs)
        else:self.node('Identity',[sliced],outputs)
    def random(self,distribution,shape,outputs,seed=-1,mean=0,stddev=1,low=0,high=1,prob=0.5):
        dummy=self.node('ConstantOfShape',[shape],value=NH.from_array(np.asarray([0],dtype=np.float32)));attrs={'dtype':T.FLOAT}
        if seed>=0:attrs['seed']=float(seed)
        if distribution=='normal':attrs.update(mean=float(mean),scale=float(stddev));self.node('RandomNormalLike',[dummy],outputs,**attrs)
        else:
            attrs.update(low=float(low) if distribution=='uniform' else 0.0,high=float(high) if distribution=='uniform' else 1.0);result=self.node('RandomUniformLike',[dummy],**attrs)
            if distribution=='bernoulli':result=self.node('Cast',[self.node('Less',[result,self.const(prob,dtype=np.float32)])],to=T.FLOAT)
            self.node('Identity',[result],outputs)
        self.inventory.append({'operator':'random_generator','precisionNote':'Distribution parameters are preserved; Apple and ONNX random sequences for a seed need not match.'})
    def list_write(self,values,index,value,outputs):
        fixed=values in self.fixed_lists
        if fixed:self.fixed_lists.update(outputs)
        shape=self.shapes.get(values);require(shape is not None,'tensor-list write requires known element rank');rank=len(shape);dtype=DTYPES.get(self.types.get(values,'FLOAT32'),np.float32);length=self.node('Gather',[self.node('Shape',[values]),self.const(0,dtype=np.int64)],axis=0)
        known_index=self.constants.get(index,self.known.get(index))
        if fixed and shape[0] is not None and known_index is not None:require(0<=int(np.asarray(known_index).item())<shape[0],'tensor-list index exceeds its fixed length')
        if fixed:
            for output in outputs:
                if self.shapes.get(output) is not None:self.shapes[output][0]=shape[0]
        if any(dim is None for dim in shape[1:]):
            # Public MIL list element dimensions may be symbolic strings. The
            # first write resolves their common shape; preserve initial slots.
            full_shape=self.node('Concat',[self.node('Reshape',[length,self.const([1],dtype=np.int64)]),self.node('Shape',[value])],axis=0);empty=self.node('Equal',[self.node('Size',[values]),self.const(0,dtype=np.int64)]);initialized=self.unique('list_initialized');unchanged=self.unique('list_unchanged');element_type=tensor_dtype(self.types.get(values,'FLOAT32'));then_body=H.make_graph([H.make_node('ConstantOfShape',[full_shape],[initialized],value=NH.from_array(np.asarray([0],dtype=dtype)))],'list_resolve_shape',[],[H.make_tensor_value_info(initialized,element_type,shape)]);else_body=H.make_graph([H.make_node('Identity',[values],[unchanged])],'list_keep_shape',[],[H.make_tensor_value_info(unchanged,element_type,shape)]);resolved=self.node('If',[empty],then_branch=then_body,else_branch=else_body);self.shapes[resolved]=shape;self.types[resolved]=self.types.get(values,'FLOAT32');values=resolved
        index=self.node('Cast',[index],to=T.INT64);extra=self.const(0,dtype=np.int64) if fixed else self.binary('Max',self.binary('Sub',self.binary('Add',index,self.const(1,dtype=np.int64)),length),self.const(0,dtype=np.int64));pads=self.node('Concat',[self.const([0]*rank,dtype=np.int64),self.node('Reshape',[extra,self.const([1],dtype=np.int64)]),self.const([0]*(rank-1),dtype=np.int64)],axis=0);padded=self.node('Pad',[values,pads,self.const(0,dtype=dtype)]);self.node('ScatterND',[padded,self.node('Reshape',[index,self.const([1,1],dtype=np.int64)]),self.node('Unsqueeze',[value,self.const([0],dtype=np.int64)])],outputs)
    def recurrent(self,x,initial_h,initial_c,kind,weights,recursion,bias,activations,reverse=False,sequence=True,preclip=None,cellclip=None,peephole=None,coupled=False,forget_bias=False,reset_after=False):
        shape=self.shapes.get(x);require(shape and len(shape)==3 and all(shape),'recurrent conversion requires fixed sequence,batch,input dimensions');steps,batch,_=shape;hidden=recursion[0].shape[0];child=Graph();child.reserved=set(self.reserved)|set(self.shapes)|set(self.constants);iteration=child.unique('time');condition=child.unique('continue');h=child.unique('hidden');c=child.unique('cell');infos=[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(condition,T.BOOL,[]),H.make_tensor_value_info(h,T.FLOAT,[batch,hidden])]
        if kind=='lstm':infos.append(H.make_tensor_value_info(c,T.FLOAT,[batch,hidden]))
        index=child.binary('Sub',child.const(steps-1,dtype=np.int64),iteration) if reverse else iteration;current=child.node('Gather',[x,index],axis=0)
        def activate(value,activation):
            name,alpha,beta=activation if isinstance(activation,tuple) else (activation,None,None);name=name.lower()
            if name=='linear':return child.binary('Add',child.binary('Mul',value,child.const(1 if alpha is None else alpha,dtype=np.float32)),child.const(0 if beta is None else beta,dtype=np.float32))
            if name=='scaled_tanh':return child.binary('Mul',child.node('Tanh',[child.binary('Mul',value,child.const(2/3 if beta is None else beta,dtype=np.float32))]),child.const(1.7159 if alpha is None else alpha,dtype=np.float32))
            if name=='hard_sigmoid':return child.node('HardSigmoid',[value],alpha=float(0.2 if alpha is None else alpha),beta=float(0.5 if beta is None else beta))
            mapping={'sigmoid':'Sigmoid','tanh':'Tanh','relu':'Relu','softsign':'Softsign','softplus':'Softplus'};require(name in mapping,f'unsupported recurrent activation {name}');return child.node(mapping[name],[value])
        def affine(gate,state=h,peephole_state=None,extra=0):
            value=child.binary('Add',child.node('MatMul',[current,child.const(weights[gate].T,dtype=np.float32)]),child.node('MatMul',[state,child.const(recursion[gate].T,dtype=np.float32)]));value=child.binary('Add',value,child.const(bias[gate],dtype=np.float32))
            if peephole_state is not None and peephole is not None:value=child.binary('Add',value,child.binary('Mul',peephole_state,child.const(peephole[gate],dtype=np.float32)))
            if extra:value=child.binary('Add',value,child.const(extra,dtype=np.float32))
            return value
        def clipped(value):return child.node('Clip',[value,child.const(-preclip,dtype=np.float32),child.const(preclip,dtype=np.float32)]) if preclip is not None else value
        if kind=='rnn':next_h=activate(clipped(affine(0)),activations[0]);states=[next_h]
        elif kind=='gru':
            reset=activate(clipped(affine(0)),activations[0]);update=activate(clipped(affine(2)),activations[0])
            if reset_after:
                candidate=child.binary('Add',child.node('MatMul',[current,child.const(weights[1].T,dtype=np.float32)]),child.binary('Mul',reset,child.node('MatMul',[h,child.const(recursion[1].T,dtype=np.float32)])));candidate=child.binary('Add',candidate,child.const(bias[1],dtype=np.float32))
            else:candidate=affine(1,child.binary('Mul',h,reset))
            candidate=activate(clipped(candidate),activations[1]);next_h=child.binary('Add',child.binary('Mul',child.binary('Sub',child.const(1,dtype=np.float32),update),candidate),child.binary('Mul',update,h));states=[next_h]
        else:
            input_gate=activate(clipped(affine(0,peephole_state=c)),activations[0]);forget=child.binary('Sub',child.const(1,dtype=np.float32),input_gate) if coupled else activate(clipped(affine(1,peephole_state=c,extra=1 if forget_bias else 0)),activations[0]);cell_gate=activate(clipped(affine(3)),activations[1]);next_c=child.binary('Add',child.binary('Mul',forget,c),child.binary('Mul',input_gate,cell_gate))
            if cellclip is not None:next_c=child.node('Clip',[next_c,child.const(-cellclip,dtype=np.float32),child.const(cellclip,dtype=np.float32)])
            output_gate=activate(clipped(affine(2,peephole_state=next_c)),activations[0]);next_h=child.binary('Mul',output_gate,activate(next_c,activations[2]));states=[next_h,next_c]
        scan=child.unique('output_step');child.node('Identity',[next_h],[scan]);outinfos=[H.make_tensor_value_info(condition,T.BOOL,[])]+[H.make_tensor_value_info(state,T.FLOAT,[batch,hidden]) for state in states]+[H.make_tensor_value_info(scan,T.FLOAT,[batch,hidden])];body=H.make_graph(child.nodes,'recurrent_steps',infos,outinfos,list(child.initializers.values()));outputs=[self.unique('final_hidden')]+([self.unique('final_cell')] if kind=='lstm' else [])+[self.unique('sequence')];self.node('Loop',[self.const(steps,dtype=np.int64),self.const(True),initial_h]+([initial_c] if kind=='lstm' else []),outputs,body=body);self.shapes[outputs[0]]=[batch,hidden]
        if kind=='lstm':self.shapes[outputs[1]]=[batch,hidden]
        self.shapes[outputs[-1]]=[steps,batch,hidden];result=outputs[-1] if sequence else self.node('Unsqueeze',[outputs[0],self.const([0],dtype=np.int64)]);self.shapes[result]=[steps if sequence else 1,batch,hidden];return result,outputs[0],outputs[1] if kind=='lstm' else None
    def diagnose(self,name,kind,inputs,outputs,error): self.errors.append({'name':name,'operator':kind,'inputs':inputs,'outputs':outputs,'inputShapes':{x:self.shapes.get(x) for x in inputs},'outputShapes':{x:self.shapes.get(x) for x in outputs},'inputTypes':{x:self.types.get(x,'float32 (NN)') for x in inputs},'outputTypes':{x:self.types.get(x,'float32 (NN)') for x in outputs},'reason':str(error)})

def lower_mil(graph, spec, model_path):
    require('main' in spec.mlProgram.functions,'MIL has no main function');function=spec.mlProgram.functions['main']
    require(bool(function.block_specializations),'MIL has no entry block');block=function.block_specializations[sorted(function.block_specializations)[-1]]
    for feature in function.inputs: graph.shapes.setdefault(feature.name,dims(feature.type.tensorType));graph.types[feature.name]=MIL_pb2.DataType.Name(feature.type.tensorType.dataType)
    return lower_mil_block(graph,block,model_path)

def tensor_dtype(kind):
    return {'FLOAT16':T.FLOAT,'FLOAT32':T.FLOAT,'FLOAT64':T.DOUBLE,'INT8':T.INT8,'UINT8':T.UINT8,'INT16':T.INT16,'UINT16':T.UINT16,'INT32':T.INT32,'UINT32':T.UINT32,'INT64':T.INT64,'UINT64':T.UINT64,'BOOL':T.BOOL}.get(kind,T.FLOAT)

def record_mil_type(graph,name,value):
    if value.HasField('listType'):
        element=value.listType.type;require(element.HasField('tensorType'),'only homogeneous tensor lists are supported');graph.shapes[name]=[None]+dims(element.tensorType);graph.types[name]=MIL_pb2.DataType.Name(element.tensorType.dataType)
    elif value.HasField('tensorType'):graph.shapes[name]=dims(value.tensorType);graph.types[name]=MIL_pb2.DataType.Name(value.tensorType.dataType)
    else:raise Unsupported('MIL value is neither a tensor nor a homogeneous tensor list')

def mil_subgraph(parent,block,model_path,name,outputs=None,return_fixed=False):
    child=Graph();child.shapes=dict(parent.shapes);child.types=dict(parent.types);child.constants=dict(parent.constants);child.reserved=set(parent.reserved)|set(parent.shapes)|set(parent.constants)
    child.fixed_lists=set(parent.fixed_lists)
    values=lower_mil_block(child,block,model_path);require(not block.inputs,'conditional MIL blocks must not take explicit inputs');require(outputs is None or len(values)==len(outputs),'conditional branch output count differs')
    infos=[]
    for value in values:
        child.initializer(value);alias=child.unique('branch_result');child.node('Identity',[value],[alias]);infos.append(H.make_tensor_value_info(alias,tensor_dtype(child.types.get(value,'FLOAT32')),child.shapes.get(value)))
    parent.inventory.extend(dict(row,block=name) for row in child.inventory);parent.errors.extend(dict(row,block=name) for row in child.errors)
    result=H.make_graph(child.nodes,name,[],infos,list(child.initializers.values()))
    return (result,[value in child.fixed_lists for value in values]) if return_fixed else result

def inline_mil_block(graph,block,bindings,model_path,prefix):
    """Bind SSA block arguments without exposing MIL block-local names to siblings."""
    def renamed(source,outer,suffix):
        result=MIL_pb2.Block();result.CopyFrom(source);mapping=dict(outer)
        for operation in source.operations:
            for output in operation.outputs:mapping[output.name]=graph.unique(suffix+output.name)
        del result.inputs[:]
        for operation in result.operations:
            for binding in operation.inputs.values():
                for argument in binding.arguments:argument.name=mapping.get(argument.name,argument.name)
            for output in operation.outputs:output.name=mapping[output.name]
            for index,nested in enumerate(operation.blocks):
                local=dict(mapping)
                for parameter in nested.inputs:local[parameter.name]=graph.unique(suffix+parameter.name)
                replacement=renamed(nested,local,suffix+f'block{index}_')
                # Nested Loop block arguments remain explicit until their own lowering.
                for parameter in nested.inputs:
                    item=replacement.inputs.add();item.CopyFrom(parameter);item.name=local[parameter.name]
                nested.CopyFrom(replacement)
        for index,name in enumerate(result.outputs):result.outputs[index]=mapping.get(name,name)
        return result
    require(len(block.inputs)==len(bindings),'MIL block argument count differs from bound values')
    aliases={parameter.name:value for parameter,value in zip(block.inputs,bindings)}
    return lower_mil_block(graph,renamed(block,aliases,prefix),model_path)

def lower_mil_loop(graph,operation,args,outputs,model_path,name):
    require(len(operation.blocks)==2,'while_loop requires condition and body blocks');condition,body=operation.blocks;values=[graph.initializer(x) for x in args.get('loop_vars',[])];require(len(values)==len(outputs)==len(body.outputs),'while_loop variable/output count mismatch')
    initial=inline_mil_block(graph,condition,values,model_path,name+'_initial_');require(len(initial)==1,'loop condition must return one boolean');child=Graph();child.shapes=dict(graph.shapes);child.types=dict(graph.types);child.constants=dict(graph.constants);child.reserved=set(graph.reserved)|set(graph.shapes)|set(graph.constants)
    child.fixed_lists=set(graph.fixed_lists)
    iteration=child.unique('loop_iteration');condition_input=child.unique('loop_condition');loop_inputs=[];infos=[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(condition_input,T.BOOL,[])]
    for source in values:
        parameter=child.unique('loop_value');child.shapes[parameter]=graph.shapes.get(source);child.types[parameter]=graph.types.get(source,'FLOAT32');loop_inputs.append(parameter);infos.append(H.make_tensor_value_info(parameter,tensor_dtype(child.types[parameter]),child.shapes[parameter]))
        if source in graph.fixed_lists:child.fixed_lists.add(parameter)
    results=inline_mil_block(child,body,loop_inputs,model_path,name+'_body_');after=inline_mil_block(child,condition,results,model_path,name+'_condition_');require(len(after)==1,'loop condition must return one boolean');outinfos=[]
    for source,value,output in zip(values,results,outputs):
        require((source in graph.fixed_lists)==(value in child.fixed_lists),'loop changes tensor-list dynamic_length type')
        if source in graph.fixed_lists:graph.fixed_lists.add(output)
    for value in after+results:
        child.initializer(value);alias=child.unique('loop_result');child.node('Identity',[value],[alias]);outinfos.append(H.make_tensor_value_info(alias,tensor_dtype(child.types.get(value,'FLOAT32')),child.shapes.get(value)))
    graph.inventory.extend(dict(row,block=name) for row in child.inventory);graph.errors.extend(dict(row,block=name) for row in child.errors);loop=H.make_graph(child.nodes,name+'_loop',infos,outinfos,list(child.initializers.values()));graph.node('Loop',['',graph.initializer(initial[0])]+values,outputs,body=loop)

def lower_mil_block(graph,block,model_path):
    for operation in block.operations:
        for output in operation.outputs:
            try:record_mil_type(graph,output.name,output.type)
            except Exception as error:graph.diagnose(output.name,operation.type,[],[output.name],error)
        graph.inventory.append({'name':operation.outputs[0].name if operation.outputs else '', 'operator':operation.type,'outputs':[{'name':o.name,'shape':graph.shapes.get(o.name),'dtype':graph.types.get(o.name,'unsupported value type')} for o in operation.outputs]})
        if operation.type=='const':
            try: graph.constants[operation.outputs[0].name]=mil_value(operation.attributes['val'],model_path)
            except Exception as error: graph.diagnose(operation.outputs[0].name,'const',[],[o.name for o in operation.outputs],error)
    unary={'abs':'Abs','acos':'Acos','asin':'Asin','atan':'Atan','atanh':'Atanh','ceil':'Ceil','cos':'Cos','cosh':'Cosh','erf':'Erf','exp':'Exp','floor':'Floor','log':'Log','reciprocal':'Reciprocal','round':'Round','sign':'Sign','sin':'Sin','sinh':'Sinh','sqrt':'Sqrt','square':None,'tan':'Tan','tanh':'Tanh','relu':'Relu','sigmoid':'Sigmoid','softplus':'Softplus','softsign':'Softsign','logical_not':'Not'}
    binary={'add':'Add','sub':'Sub','mul':'Mul','real_div':'Div','pow':'Pow','maximum':'Max','minimum':'Min','equal':'Equal','not_equal':None,'less':'Less','less_equal':'LessOrEqual','greater':'Greater','greater_equal':'GreaterOrEqual','logical_and':'And','logical_or':'Or','logical_xor':'Xor','floor_div':None}
    for op in block.operations:
        if op.type=='const':continue
        kind=op.type;outs=[o.name for o in op.outputs];name=outs[0] if outs else kind
        args={key:[a.name for a in binding.arguments] for key,binding in op.inputs.items()}
        def one(key): require(key in args and len(args[key])==1,f'missing/non-scalar binding {key}');return args[key][0]
        def val(key,default=None): return graph.scalar(one(key)) if key in args else default
        def arr(key,default=None): return np.asarray(graph.val(one(key))).reshape(-1).tolist() if key in args else default
        def ref(key): return graph.initializer(one(key))
        try:
            require(not op.blocks or kind in ('cond','while_loop'),'nested MIL control-flow blocks require explicit conversion')
            if kind=='cond':
                require(len(op.blocks)==2,'conditional operation requires true/false blocks');true_graph,true_fixed=mil_subgraph(graph,op.blocks[0],model_path,name+'_true',outs,True);false_graph,false_fixed=mil_subgraph(graph,op.blocks[1],model_path,name+'_false',outs,True);require(true_fixed==false_fixed,'conditional branches differ in tensor-list dynamic_length type');graph.fixed_lists.update(output for output,fixed in zip(outs,true_fixed) if fixed);graph.node('If',[ref('pred')],outs,then_branch=true_graph,else_branch=false_graph)
            elif kind=='while_loop':lower_mil_loop(graph,op,args,outs,model_path,name)
            elif kind=='make_list':
                elements=[graph.scalar(value) for value in args.get('elem_shape',[])];shape=[0 if isinstance(value,str) else int(value) for value in elements];require(all(x>=0 for x in shape),'tensor-list dimensions cannot be negative');length=ref('init_length') if 'init_length' in args else graph.const(1,dtype=np.int32);full_shape=graph.node('Concat',[graph.node('Reshape',[graph.node('Cast',[length],to=T.INT64),graph.const([1],dtype=np.int64)]),graph.const(shape,dtype=np.int64)],axis=0);graph.node('ConstantOfShape',[full_shape],outs,value=NH.from_array(np.asarray([0],dtype=DTYPES.get(graph.types.get(name,'FLOAT32'),np.float32))))
                if not val('dynamic_length',True):graph.fixed_lists.update(outs)
                known_length=graph.constants.get(length,graph.known.get(length))
                if known_length is not None:graph.shapes[name][0]=int(np.asarray(known_length).item())
            elif kind=='list_length':graph.node('Cast',[graph.node('Gather',[graph.node('Shape',[ref('ls')]),graph.const(0,dtype=np.int64)],axis=0)],outs,to=T.INT32)
            elif kind in ('list_read','list_gather'):graph.node('Gather',[ref('ls'),graph.node('Cast',[ref('index' if kind=='list_read' else 'indices')],to=T.INT64)],outs,axis=0)
            elif kind=='list_write':graph.list_write(ref('ls'),ref('index'),ref('value'),outs)
            elif kind=='list_scatter':
                values,indices,updates=ref('ls'),ref('indices'),ref('value');shape=graph.shapes[one('ls')];child=Graph();child.reserved=set(graph.reserved)|set(graph.shapes)|set(graph.constants);iteration=child.unique('list_index');condition=child.unique('list_condition');current=child.unique('list_values');child.shapes[current]=shape;child.types[current]=graph.types[one('ls')];dtype=tensor_dtype(child.types[current]);index=child.node('Gather',[indices,iteration],axis=0);value=child.node('Gather',[updates,iteration],axis=0);result=child.unique('updated_list')
                if values in graph.fixed_lists:child.fixed_lists.add(current);graph.fixed_lists.update(outs)
                child.list_write(current,index,value,[result]);infos=[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(condition,T.BOOL,[]),H.make_tensor_value_info(current,dtype,shape)];outinfos=[H.make_tensor_value_info(condition,T.BOOL,[]),H.make_tensor_value_info(result,dtype,shape)];body=H.make_graph(child.nodes,name+'_scatter',infos,outinfos,list(child.initializers.values()));graph.node('Loop',[graph.node('Size',[indices]),graph.const(True),values],outs,body=body)
            elif kind in unary:
                if kind=='square': graph.node('Mul',[ref('x'),ref('x')],outs)
                elif kind=='log':graph.node('Log',[graph.binary('Add',ref('x'),graph.const(val('epsilon',0 if graph.types.get(one('x'))=='FLOAT16' else 1e-45),dtype=np.float32))],outs)
                else: graph.node(unary[kind],[ref('x')],outs)
            elif kind in binary:
                if kind=='not_equal': graph.node('Not',[graph.node('Equal',[ref('x'),ref('y')])],outs)
                elif kind=='floor_div':
                    x,y=ref('x'),ref('y');dtype=graph.types.get(name,'FLOAT32')
                    if dtype.startswith('INT') or dtype.startswith('UINT'):
                        require(dtype in DTYPES,f'unknown integer division dtype {dtype}');zero=graph.const(0,dtype=DTYPES[dtype]);quotient=graph.node('Div',[x,y]);remainder=graph.node('Mod',[x,y],fmod=1)
                        nonzero=graph.node('Not',[graph.node('Equal',[remainder,zero])]);different=graph.node('Xor',[graph.node('Less',[x,zero]),graph.node('Less',[y,zero])]);adjust=graph.node('Cast',[graph.node('And',[nonzero,different])],to=getattr(T,dtype))
                        graph.node('Sub',[quotient,adjust],outs)
                    else:graph.node('Floor',[graph.node('Div',[x,y])],outs)
                else:graph.node(binary[kind],[ref('x'),ref('y')],outs)
            elif kind in ('identity','cast'):
                target=val('dtype','fp32');mapping={'fp16':T.FLOAT,'fp32':T.FLOAT,'fp64':T.DOUBLE,'int8':T.INT8,'uint8':T.UINT8,'int16':T.INT16,'uint16':T.UINT16,'int32':T.INT32,'uint32':T.UINT32,'int64':T.INT64,'uint64':T.UINT64,'bool':T.BOOL}
                if kind=='identity': graph.node('Identity',[ref('x')],outs)
                else: require(target in mapping,f'unsupported cast dtype {target}');graph.node('Cast',[ref('x')],outs,to=mapping[target])
            elif kind in ('conv','conv_transpose','conv_quantized'):
                padding=val('pad_type','valid');rank=len(graph.shapes[one('weight')])-2;attrs={'strides':arr('strides',[1]*rank),'dilations':arr('dilations',[1]*rank),'group':int(val('groups',1))}
                if padding=='custom':attrs['pads']=pairs_to_pads(arr('pad'))
                elif padding in ('same','same_lower','valid'):attrs['auto_pad']={'same':'SAME_UPPER','same_lower':'SAME_LOWER','valid':'VALID'}[padding]
                else:raise Unsupported(f'unsupported conv padding {padding}')
                if kind=='conv_transpose' and 'output_shape' in args:attrs['output_shape']=arr('output_shape')[-len(attrs['strides']):]
                weight=ref('weight')
                if kind=='conv_quantized':
                    require(val('quantization_type')=='linear','experimental quantized-conv LUT has no published palette binding');shape=[-1]+[1]*(rank+1);scale=graph.node('Reshape',[ref('quant_scale'),graph.const(shape,dtype=np.int64)]);bias=graph.node('Reshape',[ref('quant_bias'),graph.const(shape,dtype=np.int64)]);weight=graph.binary('Add',graph.binary('Mul',graph.node('Cast',[weight],to=T.FLOAT),scale),bias)
                graph.node('ConvTranspose' if kind=='conv_transpose' else 'Conv',[ref('x'),weight]+([graph.node('Cast',[ref('bias')],to=T.FLOAT) if kind=='conv_quantized' else ref('bias')] if 'bias' in args else []),outs,**attrs)
            elif kind in ('leaky_relu','elu'):graph.node({'leaky_relu':'LeakyRelu','elu':'Elu'}[kind],[ref('x')],outs,alpha=float(val('alpha',1.0)))
            elif kind=='thresholded_relu':graph.node('Where',[graph.node('GreaterOrEqual',[ref('x'),graph.const(val('alpha',1),dtype=np.float32)]),ref('x'),graph.const(0,dtype=np.float32)],outs)
            elif kind=='gelu':graph.gelu(ref('x'),outs,val('mode','EXACT'))
            elif kind=='silu':graph.node('Mul',[ref('x'),graph.node('Sigmoid',[ref('x')])],outs)
            elif kind=='clamped_relu':graph.node('Min',[graph.node('LeakyRelu',[ref('x')],alpha=float(val('alpha'))),graph.const(val('beta'),dtype=np.float32)],outs)
            elif kind=='scaled_tanh':graph.node('Mul',[graph.node('Tanh',[graph.binary('Mul',ref('x'),graph.const(val('beta'),dtype=np.float32))]),graph.const(val('alpha'),dtype=np.float32)],outs)
            elif kind=='softplus_parametric':
                shape=graph.shapes.get(one('x'));require(shape and len(shape)>=3,'parametric softplus requires known rank');broadcast=[1,shape[1]]+[1]*(len(shape)-2);alpha=graph.node('Reshape',[ref('alpha'),graph.const(broadcast,dtype=np.int64)]);beta=graph.node('Reshape',[ref('beta'),graph.const(broadcast,dtype=np.int64)]);graph.node('Mul',[alpha,graph.node('Softplus',[graph.binary('Mul',ref('x'),beta)])],outs)
            elif kind in ('inverse','rsqrt'):
                denominator=graph.binary('Add',ref('x'),graph.const(val('epsilon',1e-4),dtype=np.float32));graph.node('Reciprocal',[graph.node('Sqrt',[denominator]) if kind=='rsqrt' else denominator],outs)
            elif kind=='exp2':
                source=ref('x');integer=graph.types.get(one('x'),'FLOAT32').startswith(('INT','UINT'));result=graph.node('Pow',[graph.const(2,dtype=np.float32),graph.node('Cast',[source],to=T.FLOAT) if integer else source]);graph.node('Cast' if integer else 'Identity',[result],outs,**({'to':tensor_dtype(graph.types.get(name,'INT32'))} if integer else {}))
            elif kind=='sigmoid_hard':graph.node('HardSigmoid',[ref('x')],outs,alpha=float(val('alpha',0.2)),beta=float(val('beta',0.5)))
            elif kind=='linear_activation':graph.node('Add',[graph.binary('Mul',ref('x'),graph.const(val('alpha',1),dtype=np.float32)),graph.const(val('beta',0),dtype=np.float32)],outs)
            elif kind=='prelu':
                shape=graph.shapes.get(one('x'));require(shape and len(shape)>=3,'PReLU requires a channel/spatial tensor');alpha=graph.node('Reshape',[ref('alpha'),graph.const([1,shape[1]]+[1]*(len(shape)-2),dtype=np.int64)]);graph.node('PRelu',[ref('x'),alpha],outs)
            elif kind=='clip':graph.node('Clip',[ref('x'),graph.const(val('alpha'),dtype=np.float32),graph.const(val('beta'),dtype=np.float32)],outs)
            elif kind=='relu6':graph.node('Clip',[ref('x'),graph.const(0,dtype=np.float32),graph.const(6,dtype=np.float32)],outs)
            elif kind=='threshold':graph.node('Max',[ref('x'),graph.const(val('alpha'),dtype=DTYPES.get(graph.types.get(name,'FLOAT32'),np.float32))],outs)
            elif kind=='mod':
                if graph.types.get(name,'FLOAT32').startswith(('INT','UINT')):graph.node('Mod',[ref('x'),ref('y')],outs,fmod=0)
                else:graph.node('Sub',[ref('x'),graph.binary('Mul',graph.node('Floor',[graph.binary('Div',ref('x'),ref('y'))]),ref('y'))],outs)
            elif kind=='fill_like':graph.node('ConstantOfShape',[graph.node('Shape',[ref('ref_tensor')])],outs,value=NH.from_array(np.asarray([val('value',0)],dtype=DTYPES.get(graph.types.get(name,'FLOAT32'),np.float32))))
            elif kind=='einsum':graph.node('Einsum',[graph.initializer(x) for x in args['values']],outs,equation=val('equation'))
            elif kind=='scaled_dot_product_attention':
                shape=graph.shapes.get(one('key'));require(shape and shape[-1],'attention requires known embedding size');perm=list(range(len(shape)));perm[-2:]=perm[-2:][::-1];key=graph.node('Transpose',[ref('key')],perm=perm);scores=graph.binary('Div',graph.node('MatMul',[ref('query'),key]),graph.const(math.sqrt(shape[-1]),dtype=np.float32))
                if 'attn_mask' in args:
                    mask=ref('attn_mask')
                    if graph.types.get(one('attn_mask'))=='BOOL':mask=graph.node('Where',[mask,graph.const(0,dtype=np.float32),graph.const(-np.inf,dtype=np.float32)])
                    scores=graph.binary('Add',scores,mask)
                graph.node('MatMul',[graph.node('Softmax',[scores],axis=-1),ref('value')],outs)
            elif kind in ('concat','stack'):
                axis=int(val('axis'));inputs=[graph.initializer(x) for x in args['values']]
                if kind=='stack':inputs=[graph.node('Unsqueeze',[x,graph.const([axis],dtype=np.int64)]) for x in inputs]
                if kind=='concat' and val('interleave',False):
                    shape=graph.shapes.get(inputs[0]);require(shape and all(shape) and all(graph.shapes.get(x)==shape for x in inputs),'interleaved concat requires equal fixed shapes');axis%=len(shape)
                    expanded=[graph.node('Unsqueeze',[x,graph.const([axis+1],dtype=np.int64)]) for x in inputs];combined=graph.node('Concat',expanded,axis=axis+1);result=list(shape);result[axis]*=len(inputs);graph.node('Reshape',[combined,graph.const(result,dtype=np.int64)],outs)
                else:graph.node('Concat',inputs,outs,axis=axis)
            elif kind=='split':
                inputs=[ref('x')];axis=int(val('axis',0));splits=arr('split_sizes')
                if splits is not None:inputs.append(graph.const(splits,dtype=np.int64))
                graph.node('Split',inputs,outs,axis=axis)
            elif kind=='transpose':graph.node('Transpose',[ref('x')],outs,perm=arr('perm'))
            elif kind=='reshape':graph.node('Reshape',[ref('x'),graph.const(arr('shape'),dtype=np.int64) if one('shape') in graph.constants else graph.node('Cast',[ref('shape')],to=T.INT64)],outs)
            elif kind=='reshape_like':
                count=len(args.get('ref_tensors',[]));require(all(len(args.get(key,[]))==count for key in ('begins','ends','end_masks')),'reshape-like tuple length mismatch');pieces=[]
                for source,start,end,mask in zip(args['ref_tensors'],args['begins'],args['ends'],args['end_masks']):pieces.append(graph.node('Slice',[graph.node('Shape',[graph.initializer(source)]),graph.const([graph.scalar(start)],dtype=np.int64),graph.const([np.iinfo(np.int64).max if graph.scalar(mask) else graph.scalar(end)],dtype=np.int64)]))
                graph.node('Reshape',[ref('x'),graph.node('Concat',pieces,axis=0)],outs)
            elif kind in ('squeeze','expand_dims'):
                inputs=[ref('x')];axes=arr('axes');
                if axes is not None:inputs.append(graph.const(axes,dtype=np.int64))
                graph.node('Squeeze' if kind=='squeeze' else 'Unsqueeze',inputs,outs)
            elif kind=='flatten2d':graph.node('Flatten',[ref('x')],outs,axis=int(val('axis',1)))
            elif kind in ('shape','rank'):graph.node('Cast',[graph.node('Shape' if kind=='shape' else 'Size',[ref('x')] if kind=='shape' else [graph.node('Shape',[ref('x')])])],outs,to=T.INT32)
            elif kind=='tile':graph.node('Tile',[ref('x'),graph.node('Cast',[ref('reps')],to=T.INT64)],outs)
            elif kind=='reverse':
                shape=graph.shapes.get(one('x'));require(shape is not None,'reverse requires known input rank');axes=arr('axes',list(range(len(shape))));graph.node('Slice',[ref('x'),graph.const([-1]*len(axes),dtype=np.int64),graph.const([np.iinfo(np.int64).min]*len(axes),dtype=np.int64),graph.const(axes,dtype=np.int64),graph.const([-1]*len(axes),dtype=np.int64)],outs)
            elif kind=='crop':
                shape=graph.shapes.get(one('x'));height=arr('crop_height');width=arr('crop_width');require(shape and len(shape)>=3 and len(height)==len(width)==2,'crop requires known spatial rank and two crop amounts')
                graph.node('Slice',[ref('x'),graph.const([height[0],width[0]],dtype=np.int64),graph.const([shape[-2]-height[1],shape[-1]-width[1]],dtype=np.int64),graph.const([-2,-1],dtype=np.int64)],outs)
            elif kind=='fill':
                shape=graph.node('Cast',[ref('shape')],to=T.INT64);value=val('value',0);graph.node('ConstantOfShape',[shape],outs,value=NH.from_array(np.asarray([value],dtype=np.float32 if graph.types.get(name,'FLOAT32').startswith('FLOAT') else DTYPES[graph.types[name]])))
            elif kind=='range_1d':graph.node('Range',[ref('start'),ref('end'),ref('step')],outs)
            elif kind in ('random_normal','random_uniform','random_bernoulli'):graph.random(kind.removeprefix('random_'),graph.node('Cast',[ref('shape')],to=T.INT64),outs,int(val('seed',-1)),val('mean',0),val('stddev',1),val('low',0),val('high',1),val('prob',0.5))
            elif kind=='random_categorical':
                shape=graph.shapes.get(one('x'));require(shape and all(shape),'categorical sampling requires fixed dimensions');logits=graph.node('Reshape',[ref('x'),graph.const([-1,shape[-1]],dtype=np.int64)]);mode=val('mode','logits');require(mode in ('logits','probs'),'unknown categorical input mode');logits=graph.node('Log',[logits]) if mode=='probs' else logits;attrs={'sample_size':int(val('size',1)),'dtype':T.INT32}
                if int(val('seed',-1))>=0:attrs['seed']=float(val('seed'))
                sampled=graph.node('Cast',[graph.node('Multinomial',[logits],**attrs)],to=T.FLOAT);graph.node('Reshape',[sampled,graph.const(shape[:-1]+[attrs['sample_size']],dtype=np.int64)],outs)
            elif kind=='topk':
                returns=bool(val('return_indices',True));require(len(outs)==(2 if returns else 1),'topk output count disagrees with return_indices');indices=graph.unique('topk_indices');graph.node('TopK',[ref('x'),graph.const([int(val('k',1))],dtype=np.int64)],[outs[0],indices],axis=int(val('axis',-1)),largest=int(not val('ascending',False)),sorted=int(val('sort',True)))
                if returns:
                    dtype=val('output_indices_dtype','int32');require(dtype in ('int32','uint16'),'invalid topk output index dtype');graph.node('Cast',[indices],[outs[1]],to=T.UINT16 if dtype=='uint16' else T.INT32)
            elif kind.startswith('reduce_') and kind not in ('reduce_argmax','reduce_argmin'):
                mode=kind.removeprefix('reduce_');mapping={'mean':'ReduceMean','sum':'ReduceSum','max':'ReduceMax','min':'ReduceMin','prod':'ReduceProd','l1_norm':'ReduceL1','l2_norm':'ReduceL2','log_sum':'ReduceLogSum','log_sum_exp':'ReduceLogSumExp','sum_square':'ReduceSumSquare'};require(mode in mapping,f'unsupported reduction {mode}')
                axes=arr('axes');attrs={'keepdims':int(val('keep_dims',False))};inputs=[ref('x')]
                if mode=='sum' and axes is not None:inputs.append(graph.const(axes,dtype=np.int64))
                elif axes is not None:attrs['axes']=axes
                graph.node(mapping[mode],inputs,outs,**attrs)
            elif kind in ('reduce_argmax','reduce_argmin'):graph.node('Cast',[graph.node('ArgMax' if kind.endswith('argmax') else 'ArgMin',[ref('x')],axis=int(val('axis',0)),keepdims=int(val('keep_dims',False)))],outs,to=T.INT32)
            elif kind in ('gather','gather_along_axis'):
                indices=graph.node('Cast',[ref('indices')],to=T.INT64);batch=int(val('batch_dims',0));axis=int(val('axis',0))
                if kind=='gather' and batch:
                    source=graph.shapes.get(one('x'));index_shape=graph.shapes.get(one('indices'));require(source is not None and index_shape is not None,'batched gather requires known ranks');rank=len(source);axis%=rank;require(0<=batch<=axis and batch<=len(index_shape),'invalid gather batch dimensions');before=list(range(batch,axis));after=list(range(axis+1,rank));ordered=graph.node('Transpose',[ref('x')],perm=list(range(batch))+[axis]+before+after);result=graph.node('GatherND',[ordered,graph.node('Unsqueeze',[indices,graph.const([-1],dtype=np.int64)])],batch_dims=batch);tail=len(index_shape)-batch;perm=list(range(batch))+list(range(batch+tail,batch+tail+len(before)))+list(range(batch,batch+tail))+list(range(batch+tail+len(before),len(index_shape)+len(before)+len(after)));graph.node('Transpose',[result],outs,perm=perm)
                else:graph.node('Gather' if kind=='gather' else 'GatherElements',[ref('x'),indices],outs,axis=axis)
            elif kind=='gather_nd':graph.node('GatherND',[ref('x'),graph.node('Cast',[ref('indices')],to=T.INT64)],outs,batch_dims=int(val('batch_dims',0)))
            elif kind in ('scatter','scatter_nd','scatter_along_axis'):graph.scatter(ref('data'),ref('indices'),ref('updates'),outs,kind,int(val('axis',0)),val('mode','add'))
            elif kind=='cumsum':graph.node('CumSum',[ref('x'),graph.const(val('axis',0),dtype=np.int64)],outs,exclusive=int(val('exclusive',False)),reverse=int(val('reverse',False)))
            elif kind=='non_zero':graph.node('Cast',[graph.node('Transpose',[graph.node('NonZero',[ref('x')])],perm=[1,0])],outs,to=T.INT32)
            elif kind=='one_hot':graph.node('OneHot',[graph.node('Cast',[ref('indices')],to=T.INT64),graph.node('Cast',[ref('one_hot_vector_size')],to=T.INT64),graph.const([val('off_value',0),val('on_value',1)],dtype=DTYPES.get(graph.types.get(name,'FLOAT32'),np.float32))],outs,axis=int(val('axis',-1)))
            elif kind=='argsort':
                shape=graph.shapes.get(one('x'));axis=int(val('axis',-1));require(shape and shape[axis],'argsort requires fixed axis length');index=graph.unique('sorted_indices');graph.node('TopK',[ref('x'),graph.const([shape[axis]],dtype=np.int64)],[graph.unique('sorted_values'),index],axis=axis,largest=int(not val('ascending',False)),sorted=1);graph.node('Cast',[index],outs,to=T.INT32)
            elif kind=='band_part':
                shape=graph.shapes.get(one('x'));require(shape and len(shape)>=2 and all(shape[-2:]),'band part requires fixed matrix dimensions');row,col=np.indices(shape[-2:]);lower,upper=int(val('lower',-1)),int(val('upper',-1));mask=((lower<0)|(row-col<=lower))&((upper<0)|(col-row<=upper));graph.node('Where',[graph.const(mask),ref('x'),graph.const(0,dtype=DTYPES.get(graph.types.get(name,'FLOAT32'),np.float32))],outs)
            elif kind=='reverse_sequence':graph.node('ReverseSequence',[ref('x'),graph.node('Cast',[ref('lengths')],to=T.INT64)],outs,batch_axis=int(val('batch_axis',0)),time_axis=int(val('seq_axis',1)))
            elif kind=='sliding_windows':
                shape=graph.shapes.get(one('x'));axis=int(val('axis'));size,stride=int(val('size')),int(val('stride',1));require(shape and shape[axis] and size>0 and stride>0 and size<=shape[axis],'sliding windows requires a valid fixed window axis');indices=np.arange(0,shape[axis]-size+1,stride).reshape(-1,1)+np.arange(size);graph.node('Gather',[ref('x'),graph.const(indices,dtype=np.int64)],outs,axis=axis)
            elif kind=='select':graph.node('Where',[ref('cond'),ref('a'),ref('b')],outs)
            elif kind in ('matmul','linear'):
                x=ref('x');y=ref('weight' if kind=='linear' else 'y')
                def transpose_last(value): rank=len(graph.shapes.get(value,graph.constants[value].shape if value in graph.constants else []));require(rank>=2,'unknown matmul rank');perm=list(range(rank));perm[-2:]=perm[-2:][::-1];return graph.node('Transpose',[value],perm=perm)
                if val('transpose_x',False):x=transpose_last(x)
                if kind=='linear' or val('transpose_y',False):y=transpose_last(y)
                result=graph.node('MatMul',[x,y]);graph.node('Add',[result,ref('bias')],outs) if 'bias' in args else graph.node('Identity',[result],outs)
            elif kind in ('rnn','gru','lstm'):
                direction=val('direction','forward');require(direction in ('forward','reverse','bidirectional') and (kind=='lstm' or direction!='bidirectional'),'invalid recurrent direction');count={'rnn':1,'gru':3,'lstm':4}[kind];hidden=graph.val(one('weight_hh')).shape[-1];shape=graph.shapes[one('x')];batch=shape[1];results=[];states=[];cells=[]
                for index in range(2 if direction=='bidirectional' else 1):
                    suffix='_back' if index else '';w=graph.val(one('weight_ih'+suffix));r=graph.val(one('weight_hh'+suffix));require(w.shape[0]>=count*hidden and r.shape[0]>=count*hidden,'invalid recurrent gate weights');weights=list(np.split(w[:count*hidden],count));recursion=list(np.split(r[:count*hidden],count));bias=list(np.split(graph.val(one('bias'+suffix))[:count*hidden],count)) if 'bias'+suffix in args else [np.zeros(hidden,dtype=np.float32)]*count;initial_h=ref('initial_h');initial_c=ref('initial_c') if kind=='lstm' else None
                    if direction=='bidirectional':
                        selection=graph.const(np.arange(index*hidden,(index+1)*hidden),dtype=np.int64);initial_h=graph.node('Gather',[initial_h,selection],axis=1);graph.shapes[initial_h]=[batch,hidden];initial_c=graph.node('Gather',[initial_c,selection],axis=1);graph.shapes[initial_c]=[batch,hidden]
                    acts=[val('activation','tanh')] if kind=='rnn' else [val('recurrent_activation','sigmoid'),val('activation','tanh')] if kind=='gru' else [val('recurrent_activation','sigmoid'),val('cell_activation','tanh'),val('activation','tanh')];peephole=list(np.split(graph.val(one('peephole'+suffix)),3)) if 'peephole'+suffix in args else None;result,state,cell=graph.recurrent(ref('x'),initial_h,initial_c,kind,weights,recursion,bias,acts,reverse=direction=='reverse' or index==1,sequence=bool(val('output_sequence',False)),cellclip=val('clip'),peephole=peephole,reset_after=kind=='gru')
                    if index and val('output_sequence',False):result=graph.node('Slice',[result,graph.const([-1],dtype=np.int64),graph.const([np.iinfo(np.int64).min],dtype=np.int64),graph.const([0],dtype=np.int64),graph.const([-1],dtype=np.int64)])
                    results.append(result);states.append(state);cells.append(cell)
                values=[graph.node('Concat',results,axis=2) if len(results)>1 else results[0],graph.node('Concat',states,axis=1) if len(states)>1 else states[0]]
                if kind=='lstm':values.append(graph.node('Concat',cells,axis=1) if len(cells)>1 else cells[0])
                require(len(values)==len(outs),'recurrent output count differs');[graph.node('Identity',[value],[output]) for value,output in zip(values,outs)]
            elif kind in ('avg_pool','max_pool','l2_pool'):
                kernels=arr('kernel_sizes');attrs={'kernel_shape':kernels,'strides':arr('strides',[1]*len(kernels)),'ceil_mode':int(val('ceil_mode',False))};padding=val('pad_type','valid')
                if padding=='custom':attrs['pads']=pairs_to_pads(arr('pad'))
                else:require(padding in ('valid','same'),f'unsupported pool padding {padding}');attrs['auto_pad']='VALID' if padding=='valid' else 'SAME_UPPER'
                if kind=='avg_pool':attrs['count_include_pad']=int(not val('exclude_padding_from_average',False))
                if kind=='l2_pool' and attrs['ceil_mode']:
                    shape=graph.shapes.get(one('x'));require(shape and all(shape),'ceil L2 pooling requires fixed dimensions');spatial=shape[2:];rank=len(spatial);pads=attrs.get('pads',[0]*(2*rank))
                    if padding=='same':
                        total=[max((math.ceil(size/stride)-1)*stride+kernel-size,0) for size,stride,kernel in zip(spatial,attrs['strides'],kernels)];pads=[v//2 for v in total]+[v-v//2 for v in total]
                    after=[]
                    for size,kernel,stride,before,following in zip(spatial,kernels,attrs['strides'],pads[:rank],pads[rank:]):
                        count=math.ceil((size+before+following-kernel)/stride)+1
                        if before>0 and (count-1)*stride>=size+before:count-=1
                        after.append(max(following,(count-1)*stride+kernel-size-before))
                    padded=graph.node('Pad',[ref('x'),graph.const([0,0]+pads[:rank]+[0,0]+after,dtype=np.int64),graph.const(0,dtype=np.float32)]);square=graph.binary('Mul',padded,padded);averaged=graph.node('AveragePool',[square],kernel_shape=kernels,strides=attrs['strides'],count_include_pad=1);graph.node('Sqrt',[graph.binary('Mul',averaged,graph.const(math.prod(kernels),dtype=np.float32))],outs)
                else:
                    if kind=='l2_pool':attrs.pop('ceil_mode');attrs['p']=2
                    graph.node('AveragePool' if kind=='avg_pool' else 'MaxPool' if kind=='max_pool' else 'LpPool',[ref('x')],outs,**attrs)
            elif kind in ('upsample_nearest_neighbor','upsample_bilinear'):
                scales=[1,1,float(val('scale_factor_height',1)),float(val('scale_factor_width',1))];shape=graph.shapes.get(one('x'));sizes=[int(n*s) for n,s in zip(shape,scales)] if kind=='upsample_bilinear' and shape and all(shape) else None
                graph.resize(ref('x'),outs,scales=None if sizes else scales,sizes=sizes,mode='nearest' if kind.endswith('nearest_neighbor') else 'linear',coordinates='asymmetric' if kind.endswith('nearest_neighbor') else 'align_corners' if val('align_corners',True) else 'half_pixel')
            elif kind in ('resize_nearest_neighbor','resize_bilinear'):
                shape=graph.shapes.get(one('x'));require(shape is not None and len(shape)==4 and all(shape[:2]),'resize requires known N/C dimensions')
                sampling=val('sampling_mode','DEFAULT');mapping={'DEFAULT':'asymmetric','STRICT_ALIGN_CORNERS':'align_corners','ALIGN_CORNERS':'align_corners','UNALIGN_CORNERS':'half_pixel','OFFSET_CORNERS':'half_pixel'};require(sampling in mapping,f'unsupported resize sampling {sampling}');sizes=shape[:2]+[int(val('target_size_height',1)),int(val('target_size_width',1))]
                if kind=='resize_bilinear' and (sampling=='OFFSET_CORNERS' or sampling=='ALIGN_CORNERS' and 1 in sizes[-2:]):graph.sampled_resize(ref('x'),outs,sizes,sampling)
                else:graph.resize(ref('x'),outs,sizes=sizes,mode='nearest' if kind.endswith('nearest_neighbor') else 'linear',coordinates=mapping[sampling])
            elif kind=='crop_resize':graph.crop_resize(ref('x'),ref('roi'),outs,int(val('target_height',1)),int(val('target_width',1)),bool(val('normalized_coordinates',False)),float(val('spatial_scale',1)),val('box_coordinate_mode','CORNERS_HEIGHT_FIRST'),val('sampling_mode','DEFAULT'))
            elif kind=='non_maximum_suppression':graph.nms(ref('boxes'),ref('scores'),outs,ref('iou_threshold'),ref('score_threshold'),ref('max_boxes'),bool(val('per_class_suppression',False)),True)
            elif kind=='pixel_shuffle':graph.node('DepthToSpace',[ref('x')],outs,blocksize=int(val('upscale_factor')),mode='CRD')
            elif kind in ('depth_to_space','space_to_depth'):graph.node('DepthToSpace' if kind=='depth_to_space' else 'SpaceToDepth',[ref('x')],outs,blocksize=int(val('block_size')),**({'mode':'DCR'} if kind=='depth_to_space' else {}))
            elif kind=='resize':
                shape=graph.shapes.get(one('x'));require(shape and len(shape)>=3,'resize requires known rank');count=int(val('resized_dims'));require(0<count<=len(shape),'invalid resized dimension count');target=graph.node('Cast',[ref('shape')],to=T.INT64);tail=graph.node('Slice',[target,graph.const([-count],dtype=np.int64),graph.const([np.iinfo(np.int64).max],dtype=np.int64)]);original=graph.node('Shape',[ref('x')]);inherited=graph.node('Slice',[original,graph.const([-count],dtype=np.int64),graph.const([np.iinfo(np.int64).max],dtype=np.int64)]);tail=graph.node('Where',[graph.node('Equal',[tail,graph.const(0,dtype=np.int64)]),inherited,tail]);prefix=graph.node('Slice',[original,graph.const([0],dtype=np.int64),graph.const([len(shape)-count],dtype=np.int64)]);size=graph.node('Concat',[prefix,tail],axis=0);sampling=val('sampling_mode','DEFAULT');mapping={'DEFAULT':'asymmetric','STRICT_ALIGN_CORNERS':'align_corners','ALIGN_CORNERS':'align_corners','UNALIGN_CORNERS':'half_pixel'};interpolation=val('interpolation_mode','LINEAR');require(interpolation in ('LINEAR','NEAREST_NEIGHBOR'),'unknown resize interpolation')
                if sampling in ('OFFSET_CORNERS','ALIGN_CORNERS'):graph.runtime_sampled_resize(ref('x'),size,outs,sampling,count,nearest=interpolation=='NEAREST_NEIGHBOR')
                else:require(sampling in mapping,'unknown resize sampling');graph.node('Resize',[ref('x'),'','',size],outs,mode='linear' if interpolation=='LINEAR' else 'nearest',coordinate_transformation_mode=mapping[sampling],**({'nearest_mode':'floor'} if interpolation=='NEAREST_NEIGHBOR' else {}))
            elif kind in ('resample','affine'):
                coordinates=ref('coordinates') if kind=='resample' else None
                if kind=='affine':
                    height,width=int(val('output_height')),int(val('output_width'));matrix_shape=graph.shapes.get(one('transform_matrix'));require(matrix_shape and len(matrix_shape)==2 and matrix_shape[1]==6,'affine matrix must have shape D,6');require(val('align_corners',True),'the original affine op requires align_corners');xx=np.linspace(-1,1,width,dtype=np.float32) if width>1 else np.zeros(1,dtype=np.float32);yy=np.linspace(-1,1,height,dtype=np.float32) if height>1 else np.zeros(1,dtype=np.float32);grid_x,grid_y=np.meshgrid(xx,yy);base=graph.const(np.stack([grid_x,grid_y,np.ones_like(grid_x)],-1).reshape(1,height*width,3));matrix=graph.node('Reshape',[ref('transform_matrix'),graph.const([matrix_shape[0],2,3],dtype=np.int64)]);matrix=graph.node('Transpose',[matrix],perm=[0,2,1]);coordinates=graph.node('Reshape',[graph.node('MatMul',[base,matrix]),graph.const([matrix_shape[0],height,width,2],dtype=np.int64)])
                    batch=graph.shapes[one('x')][0]
                    if matrix_shape[0]==1 and batch!=1:coordinates=graph.node('Expand',[coordinates,graph.const([batch,height,width,2],dtype=np.int64)])
                graph.resample(ref('x'),coordinates,outs,val('sampling_mode','bilinear'),val('padding_mode','constant'),val('padding_value',0),val('coordinates_mode','unnormalized' if kind=='resample' else 'normalized_minus_one_to_one'),bool(val('align_corners',True)))
            elif kind in ('space_to_batch','batch_to_space'):
                shape=graph.shapes.get(one('x'));require(shape and len(shape)==4 and all(shape),'space/batch rearrangement requires fixed NCHW');n,c,hh,ww=shape;blocks=arr('block_shape');require(len(blocks)==2 and all(b>0 for b in blocks),'invalid block shape');bh,bw=blocks;amounts=np.asarray(graph.val(one('paddings' if kind=='space_to_batch' else 'crops'))).reshape(2,2).astype(np.int64);require(np.all(amounts>=0),'negative padding/crop');top,bottom=amounts[0];left,right=amounts[1]
                if kind=='space_to_batch':
                    ph,pw=hh+top+bottom,ww+left+right;require(ph%bh==0 and pw%bw==0,'padded shape is not divisible by block shape');padded=graph.node('Pad',[ref('x'),graph.const([0,0,top,left,0,0,bottom,right],dtype=np.int64),graph.const(0,dtype=np.float32)]);shaped=graph.node('Reshape',[padded,graph.const([n,c,ph//bh,bh,pw//bw,bw],dtype=np.int64)]);shaped=graph.node('Transpose',[shaped],perm=[3,5,0,1,2,4]);graph.node('Reshape',[shaped,graph.const([n*bh*bw,c,ph//bh,pw//bw],dtype=np.int64)],outs)
                else:
                    require(n%(bh*bw)==0,'batch size is not divisible by block product');batch=n//(bh*bw);shaped=graph.node('Reshape',[ref('x'),graph.const([bh,bw,batch,c,hh,ww],dtype=np.int64)]);shaped=graph.node('Transpose',[shaped],perm=[2,3,4,0,5,1]);expanded=graph.node('Reshape',[shaped,graph.const([batch,c,hh*bh,ww*bw],dtype=np.int64)]);graph.node('Slice',[expanded,graph.const([top,left],dtype=np.int64),graph.const([hh*bh-bottom,ww*bw-right],dtype=np.int64),graph.const([2,3],dtype=np.int64)],outs)
            elif kind=='pixel_unshuffle':
                shape=graph.shapes.get(one('x'));require(shape is not None and len(shape)==4 and all(shape),'pixel_unshuffle requires fixed NCHW shape');n,c,hh,ww=shape;r=int(val('downscale_factor'));require(hh%r==0 and ww%r==0,'pixel_unshuffle shape is not divisible')
                first=graph.node('Reshape',[ref('x'),graph.const([n,c,hh//r,r,ww//r,r],dtype=np.int64)]);second=graph.node('Transpose',[first],perm=[0,1,3,5,2,4]);graph.node('Reshape',[second,graph.const([n,c*r*r,hh//r,ww//r],dtype=np.int64)],outs)
            elif kind=='pad':
                mode=val('mode','constant');require(mode in ('constant','reflect','replicate'),'unsupported pad mode');rank=len(graph.shapes[one('x')]);pad_shape=graph.shapes.get(one('pad'));require(pad_shape and len(pad_shape)==1 and pad_shape[0] is not None and pad_shape[0]%2==0 and pad_shape[0]<=2*rank,'pad vector exceeds input rank');pad=graph.node('Cast',[ref('pad')],to=T.INT64);pad=graph.node('Concat',[graph.const([0]*(2*rank-pad_shape[0]),dtype=np.int64),pad],axis=0);pad=graph.node('Reshape',[pad,graph.const([rank,2],dtype=np.int64)]);pad=graph.node('Transpose',[pad],perm=[1,0]);pad=graph.node('Reshape',[pad,graph.const([2*rank],dtype=np.int64)]);graph.node('Pad',[ref('x'),pad,graph.const(val('constant_val',0),dtype=DTYPES.get(graph.types.get(one('x'),'FLOAT32'),np.float32))],outs,mode='edge' if mode=='replicate' else mode)
            elif kind in ('slice_by_index','slice_by_size'):
                graph.slice(ref('x'),ref('begin'),ref('size' if kind=='slice_by_size' else 'end'),outs,arr('stride'),arr('begin_mask'),arr('end_mask'),arr('squeeze_mask'),kind=='slice_by_size')
            elif kind=='slice_update':
                shape=graph.shapes.get(one('x'));require(shape and all(shape),'slice update requires fixed source dimensions');ids=graph.const(np.arange(math.prod(shape),dtype=np.int64).reshape(shape));selected=graph.unique('updated_indices');graph.slice(ids,ref('begin'),ref('end'),[selected],arr('stride'),arr('begin_mask'),arr('end_mask'),arr('squeeze_mask'));flat=graph.const([-1],dtype=np.int64);result=graph.node('ScatterElements',[graph.node('Reshape',[ref('x'),flat]),graph.node('Reshape',[selected,flat]),graph.node('Reshape',[ref('update'),flat])],axis=0);graph.node('Reshape',[result,graph.const(shape,dtype=np.int64)],outs)
            elif kind=='softmax':graph.node('Softmax',[ref('x')],outs,axis=int(val('axis',-1)))
            elif kind=='batch_norm':
                shape=graph.shapes.get(one('x'));require(shape and shape[1],'batch norm requires known channel count');channels=shape[1];graph.node('BatchNormalization',[ref('x'),ref('gamma') if 'gamma' in args else graph.const(np.ones(channels,dtype=np.float32)),ref('beta') if 'beta' in args else graph.const(np.zeros(channels,dtype=np.float32)),ref('mean'),ref('variance')],outs,epsilon=float(val('epsilon',1e-5)))
            elif kind=='instance_norm':
                shape=graph.shapes.get(one('x'));require(shape and shape[1],'instance norm requires known channel count');channels=shape[1];graph.node('InstanceNormalization',[ref('x'),ref('gamma') if 'gamma' in args else graph.const(np.ones(channels,dtype=np.float32)),ref('beta') if 'beta' in args else graph.const(np.zeros(channels,dtype=np.float32))],outs,epsilon=float(val('epsilon',1e-5)))
            elif kind=='l2_norm':
                squared=graph.binary('Mul',ref('x'),ref('x'));summed=graph.node('ReduceSum',[squared,graph.const([-3,-2,-1],dtype=np.int64)],keepdims=1);denominator=graph.node('Sqrt',[graph.binary('Add',summed,graph.const(val('epsilon',1e-6),dtype=np.float32))]);graph.node('Div',[ref('x'),denominator],outs)
            elif kind=='layer_norm':graph.normalize(ref('x'),outs,arr('axes'),float(val('epsilon',1e-5)),ref('gamma') if 'gamma' in args else None,ref('beta') if 'beta' in args else None)
            elif kind=='local_response_norm':graph.node('LRN',[ref('x')],outs,size=int(val('size')),alpha=float(val('alpha',1e-4)),beta=float(val('beta',0.75)),bias=float(val('k',1)))
            elif kind in ('quantize','dequantize'):
                x=ref('input');rank=len(graph.shapes[one('input')]);scale=graph.val(one('scale'));zero=graph.val(one('zero_point')) if 'zero_point' in args else 0
                if np.ndim(scale)==1:
                    axis=int(val('axis'));shape=[1]*rank;shape[axis]=-1;scale=np.asarray(scale).reshape(shape);zero=np.asarray(zero).reshape(shape) if np.ndim(zero)==1 else zero
                require(np.all(np.asarray(scale)!=0),'quantization scale cannot contain zero');scale=graph.const(scale,dtype=np.float32);zero=graph.const(zero,dtype=np.float32)
                if kind=='dequantize':graph.node('Mul',[graph.binary('Sub',graph.node('Cast',[x],to=T.FLOAT),zero),scale],outs)
                else:
                    dtype=val('output_dtype');require(dtype in ('uint8','int8'),'quantize requires uint8 or int8');bounds=np.iinfo(np.dtype(dtype));rounded=graph.binary('Add',graph.node('Round',[graph.binary('Div',x,scale)]),zero);clipped=graph.node('Clip',[rounded,graph.const(bounds.min,dtype=np.float32),graph.const(bounds.max,dtype=np.float32)]);graph.node('Cast',[clipped],outs,to=T.UINT8 if dtype=='uint8' else T.INT8)
            elif kind=='constexpr_cast':
                dtype=val('output_dtype');mapping={'fp16':np.float16,'fp32':np.float32,'int8':np.int8,'uint8':np.uint8,'int32':np.int32};require(dtype in mapping,'unknown constexpr cast type');value=np.asarray(graph.val(one('source_val'))).astype(mapping[dtype]);graph.constants[name]=value.astype(np.float32) if dtype=='fp16' else value
            elif kind=='constexpr_affine_dequantize':
                data=graph.val(one('quantized_data'));scale=graph.val(one('scale'));zero=graph.val(one('zero_point'));axis=int(val('axis',0));shape=[1]*data.ndim;shape[axis]=-1;graph.constants[name]=(data.astype(np.float32)-np.asarray(zero).reshape(shape))*np.asarray(scale).reshape(shape)
            elif kind=='constexpr_lut_to_dense':
                lut=graph.val(one('lut'));indices=graph.val(one('indices'))
                if 'shape' in args:
                    shape=arr('shape');bits=int(math.log2(len(lut)));indices=unpack_bits(indices.astype(np.uint8).tobytes(),bits,math.prod(shape),bitorder='little');dense=lut[indices].reshape(shape)
                else:dense=decompress_lut(indices,lut,val('vector_axis'))
                graph.constants[name]=dense
            elif kind=='constexpr_sparse_to_dense':
                mask=graph.val(one('mask'));shape=arr('shape',list(mask.shape));mask=unpack_bits(mask.astype(np.uint8).tobytes(),1,math.prod(shape),bitorder='little').reshape(shape) if 'shape' in args else mask
                values=graph.val(one('nonzero_data'));require(np.count_nonzero(mask)==values.size,'sparse value/mask count mismatch');dense=np.zeros(shape,dtype=values.dtype);dense[mask.astype(bool)]=values.reshape(-1);graph.constants[name]=dense
            elif kind=='constexpr_blockwise_shift_scale':
                graph.constants[name]=decompress_shift_scale(graph.val(one('data')),graph.val(one('scale')),graph.val(one('offset')) if 'offset' in args else None)
            elif kind in ('constexpr_lut_to_sparse','constexpr_sparse_blockwise_shift_scale'):
                require(len(outs)==2,'sparse constexpr operation requires mask and data outputs');mask=graph.val(one('indices_mask' if kind=='constexpr_lut_to_sparse' else 'data_mask'));values=graph.val(one('indices_nonzero_data' if kind=='constexpr_lut_to_sparse' else 'nonzero_data'));require(np.count_nonzero(mask)==values.size,'sparse value/mask count mismatch');data=np.zeros(mask.shape,dtype=values.dtype);data[mask.astype(bool)]=values.reshape(-1)
                if kind=='constexpr_lut_to_sparse':
                    lut=graph.val(one('lut'));data=decompress_lut(data,lut,val('vector_axis'))
                    if lut.shape[-1]>1:mask=np.repeat(mask,lut.shape[-1],axis=int(val('vector_axis')))
                else:data=decompress_shift_scale(data,graph.val(one('scale')),graph.val(one('offset')) if 'offset' in args else None)
                graph.constants[outs[0]]=mask;graph.constants[outs[1]]=data[mask.astype(bool)].reshape(-1)
            else:raise Unsupported(f'MIL operator {kind} has no verified lowering')
        except Exception as error:graph.diagnose(name,kind,[x for v in args.values() for x in v],outs,error)
    return list(block.outputs)

def lower_nn(graph,spec,input_aliases=None,return_aliases=False,control=False):
    # Training annotations do not change inference. Original MLModel prediction
    # accepts updatable networks without requesting a training operation.
    network=getattr(spec,spec.WhichOneof('Type'))
    unary={'ceil':'Ceil','floor':'Floor','sign':'Sign','round':'Round','sin':'Sin','cos':'Cos','tan':'Tan','asin':'Asin','acos':'Acos','atan':'Atan','sinh':'Sinh','cosh':'Cosh','tanh':'Tanh','asinh':'Asinh','acosh':'Acosh','atanh':'Atanh','erf':'Erf','copy':'Identity','logicalNot':'Not'}
    binary={'addBroadcastable':'Add','subtractBroadcastable':'Sub','multiplyBroadcastable':'Mul','divideBroadcastable':'Div','powBroadcastable':'Pow','minBroadcastable':'Min','maxBroadcastable':'Max','equal':'Equal','lessThan':'Less','lessEqual':'LessOrEqual','greaterThan':'Greater','greaterEqual':'GreaterOrEqual','logicalAnd':'And','logicalOr':'Or','logicalXor':'Xor'}
    graph.reserved.update(x for layer in network.layers for x in layer.output);aliases={name:name for name in graph.shapes};aliases.update(input_aliases or {});produced=set(graph.shapes)|set(graph.constants)
    for feature in spec.description.input:
        original=feature.name;value=aliases.get(original,original);shape=graph.shapes.get(value);kind=feature.type.WhichOneof('Type');declared=list(feature.type.multiArrayType.shape) if kind=='multiArrayType' else [];target=None
        if kind=='imageType' and network.imageInputShapeMapping==0 and shape and len(shape)==4:target=[1]+shape
        elif kind=='multiArrayType' and network.arrayInputShapeMapping==0 and len(declared) in (1,3):
            target=([1,1]+shape if len(shape)==3 else [1]+shape if len(shape)==4 else None) if len(declared)==3 else [1,1,math.prod(shape),1,1]
        elif kind=='multiArrayType' and network.arrayInputShapeMapping==1 and len(declared)==3 and shape and len(shape)==4:target=shape[1:]
        if target is not None:aliases[original]=graph.node('Reshape',[value,graph.const(target,dtype=np.int64)])
    for layer_index,layer in enumerate(network.layers):
        if control:
            flags=[aliases[key] for key in ('@break','@continue')];known=[graph.constants.get(key,graph.known.get(key)) for key in flags]
            if any(value is not None and bool(np.asarray(value).item()) for value in known):break
            if any(value is None for value in known):
                remaining=type(network)();remaining.CopyFrom(network);del remaining.layers[:layer_index];condition=graph.node('Not',[graph.node('Or',flags)]);aliases=nn_conditional(graph,condition,remaining,None,aliases,control=True,assume_active=True);break
        kind=layer.WhichOneof('layer');p=getattr(layer,kind);ins=[aliases.get(x,x) for x in layer.input];outs=[]
        for output in layer.output:
            value=graph.unique(output+'_value') if output in produced else output;produced.add(output);aliases[output]=value;outs.append(value)
        for name,item in zip(outs,layer.outputTensor):
            if item.dimValue:graph.shapes[name]=list(item.dimValue)
        graph.inventory.append({'name':layer.name,'operator':kind,'inputs':ins,'outputs':outs})
        if kind in ('innerProduct','batchedMatmul') and p.int8DynamicQuantize:graph.inventory[-1]['precisionNote']='Stored signed INT8 weights are dequantized with the declared scale; activation arithmetic uses FP32. Apple dynamic INT8 activation rounding/scale selection is not specified in the public model protobuf.'
        try:
            if kind=='branch':aliases=nn_conditional(graph,nn_condition(graph,ins[0]),p.ifBranch,p.elseBranch,aliases,control)
            elif kind=='loop':aliases=nn_loop(graph,p,ins,aliases)
            elif kind in ('loopBreak','loopContinue'):
                require(control,f'{kind} used outside a loop body');aliases['@continue']=graph.const(True)
                if kind=='loopBreak':aliases['@break']=graph.const(True)
            elif kind=='convolution':
                image,prefix=graph.spatial_input(ins[0]);group=p.nGroups or 1;kernel=list(p.kernelSize) or [3,3];shape=([p.kernelChannels,p.outputChannels//group] if p.isDeconvolution else [p.outputChannels,p.kernelChannels])+kernel;weights=graph.node('Reshape',[ins[1],graph.const(shape,dtype=np.int64)]) if len(ins)>1 else graph.const(nn_weights(p.weights,shape,1 if p.isDeconvolution else 0));inputs=[image,weights]
                if p.hasBias:inputs.append(ins[2] if len(ins)>2 else graph.const(nn_weights(p.bias,[p.outputChannels])))
                attrs={'strides':list(p.stride) or [1,1],'dilations':list(p.dilationFactor) or [1,1],'group':group,'kernel_shape':kernel}
                if p.WhichOneof('ConvolutionPaddingType')=='same':attrs['auto_pad']='SAME_LOWER' if p.same.asymmetryMode==1 else 'SAME_UPPER'
                else: border=list(p.valid.paddingAmounts.borderAmounts);attrs['pads']=[int(x.startEdgeSize) for x in border]+[int(x.endEdgeSize) for x in border] if border else [0]*4
                if p.isDeconvolution and p.outputShape:attrs['output_shape']=list(p.outputShape)
                result=graph.node('ConvTranspose' if p.isDeconvolution else 'Conv',inputs,name=layer.name,**attrs);graph.spatial_output(result,prefix,outs)
            elif kind=='convolution3d':
                group=p.nGroups or 1;kernels=[p.kernelDepth,p.kernelHeight,p.kernelWidth];shape=([p.inputChannels,p.outputChannels//group] if p.isDeconvolution else [p.outputChannels,p.inputChannels//group])+kernels;inputs=ins+[graph.const(nn_weights(p.weights,shape,1 if p.isDeconvolution else 0))]
                if p.hasBias:inputs.append(graph.const(nn_weights(p.bias,[p.outputChannels])))
                attrs={'kernel_shape':kernels,'strides':[p.strideDepth or 1,p.strideHeight or 1,p.strideWidth or 1],'dilations':[p.dilationDepth or 1,p.dilationHeight or 1,p.dilationWidth or 1],'group':group}
                if p.paddingType==2:attrs['auto_pad']='SAME_UPPER'
                else:attrs['pads']=[p.customPaddingFront,p.customPaddingTop,p.customPaddingLeft,p.customPaddingBack,p.customPaddingBottom,p.customPaddingRight] if p.paddingType==0 else [0]*6
                if p.isDeconvolution and p.outputShape:attrs['output_shape']=list(p.outputShape)
                graph.node('ConvTranspose' if p.isDeconvolution else 'Conv',inputs,outs,**attrs)
            elif kind in ('pooling3d','globalPooling3d'):
                if kind=='globalPooling3d':graph.node('GlobalMaxPool' if p.type==0 else 'GlobalAveragePool',ins,outs)
                else:
                    attrs={'kernel_shape':[p.kernelDepth,p.kernelHeight,p.kernelWidth],'strides':[p.strideDepth or 1,p.strideHeight or 1,p.strideWidth or 1]}
                    if p.paddingType==2:attrs['auto_pad']='SAME_UPPER'
                    else:attrs['pads']=[p.customPaddingFront,p.customPaddingTop,p.customPaddingLeft,p.customPaddingBack,p.customPaddingBottom,p.customPaddingRight] if p.paddingType==0 else [0]*6
                    if p.type==1:attrs['count_include_pad']=int(not p.countExcludePadding)
                    graph.node('MaxPool' if p.type==0 else 'AveragePool',ins,outs,**attrs)
            elif kind=='activation':
                activation=p.WhichOneof('NonlinearityType');a=getattr(p,activation);mapping={'ReLU':'Relu','leakyReLU':'LeakyRelu','tanh':'Tanh','sigmoid':'Sigmoid','sigmoidHard':'HardSigmoid','ELU':'Elu','softsign':'Softsign','softplus':'Softplus','thresholdedReLU':'ThresholdedRelu'}
                if activation=='thresholdedReLU':graph.node('Where',[graph.node('GreaterOrEqual',[ins[0],graph.const(a.alpha,dtype=np.float32)]),ins[0],graph.const(0,dtype=np.float32)],outs)
                elif activation in mapping:attrs={field.name:getattr(a,field.name) for field in a.DESCRIPTOR.fields if field.name in ('alpha','beta')};graph.node(mapping[activation],ins,outs,**attrs)
                elif activation=='linear':graph.node('Add',[graph.binary('Mul',ins[0],graph.const(a.alpha,dtype=np.float32)),graph.const(a.beta,dtype=np.float32)],outs)
                elif activation=='PReLU':
                    shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3,'PReLU requires channel/spatial dimensions');graph.node('PRelu',ins+[graph.const(nn_weights(a.alpha,[shape[-3],1,1]))],outs)
                elif activation=='scaledTanh':graph.node('Mul',[graph.node('Tanh',[graph.binary('Mul',ins[0],graph.const(a.beta,dtype=np.float32))]),graph.const(a.alpha,dtype=np.float32)],outs)
                elif activation=='parametricSoftplus':
                    shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3,'parametric softplus requires channel rank');channels=shape[-3];broadcast=[1]*(len(shape)-3)+[channels,1,1];alpha=graph.const(nn_weights(a.alpha,[channels]).reshape(broadcast));beta=graph.const(nn_weights(a.beta,[channels]).reshape(broadcast));graph.node('Mul',[alpha,graph.node('Softplus',[graph.binary('Mul',ins[0],beta)])],outs)
                else:raise Unsupported(f'unsupported activation {activation}')
            elif kind=='logicalNot':graph.node('Cast',[graph.node('Not',[graph.node('Cast',ins,to=T.BOOL)])],outs,to=T.FLOAT)
            elif kind in unary:graph.node(unary[kind],ins,outs)
            elif kind=='gelu':graph.gelu(ins[0],outs,{0:'EXACT',1:'TANH_APPROXIMATION',2:'SIGMOID_APPROXIMATION'}[p.mode])
            elif kind=='exp2':graph.node('Pow',[graph.const(2,dtype=np.float32),ins[0]],outs)
            elif kind=='clampedReLU':graph.node('Clip',[graph.node('LeakyRelu',ins,alpha=p.alpha),graph.const(-np.inf,dtype=np.float32),graph.const(p.beta,dtype=np.float32)],outs)
            elif kind=='notEqual':
                right=ins[1] if len(ins)==2 else graph.const(p.alpha,dtype=np.float32);graph.node('Cast',[graph.node('Not',[graph.node('Equal',[ins[0],right])])],outs,to=T.FLOAT)
            elif kind in ('floorDivBroadcastable','modBroadcastable'):
                if kind=='floorDivBroadcastable':graph.node('Floor',[graph.node('Div',ins)],outs)
                else:graph.node('Sub',[ins[0],graph.binary('Mul',ins[1],graph.node('Floor',[graph.node('Div',ins)]))],outs)
            elif kind=='unary':
                scaled=graph.binary('Add',graph.binary('Mul',ins[0],graph.const(p.scale or 1,dtype=np.float32)),graph.const(p.shift,dtype=np.float32));mode=p.type
                if mode==3:graph.node('Pow',[scaled,graph.const(p.alpha,dtype=np.float32)],outs)
                elif mode in (1,2):denominator=graph.binary('Add',scaled,graph.const(p.epsilon or 1e-6,dtype=np.float32));graph.node('Reciprocal',[graph.node('Sqrt',[denominator]) if mode==1 else denominator],outs)
                elif mode==5:graph.node('Log',[graph.binary('Add',scaled,graph.const(p.epsilon,dtype=np.float32))],outs)
                elif mode==7:graph.node('Max',[scaled,graph.const(p.alpha,dtype=np.float32)],outs)
                else:require(mode in (0,4,6),'unknown unary function');graph.node({0:'Sqrt',4:'Exp',6:'Abs'}[mode],[scaled],outs)
            elif kind in binary:
                inputs=ins
                if kind in ('equal','lessThan','lessEqual','greaterThan','greaterEqual'):
                    if len(inputs)==1:inputs=inputs+[graph.const(p.alpha,dtype=np.float32)]
                    result=graph.node(binary[kind],inputs);graph.node('Cast',[result],outs,to=T.FLOAT)
                elif kind in ('logicalAnd','logicalOr','logicalXor'):graph.node('Cast',[graph.node(binary[kind],[graph.node('Cast',[x],to=T.BOOL) for x in inputs])],outs,to=T.FLOAT)
                else:graph.node(binary[kind],inputs,outs)
            elif kind in ('add','multiply','average','max','min'):
                inputs=ins
                if len(inputs)==1 and kind in ('add','multiply'):inputs=inputs+[graph.const(p.alpha,dtype=np.float32)]
                result=inputs[0]
                for item in inputs[1:]:result=graph.binary({'add':'Add','multiply':'Mul','average':'Add','max':'Max','min':'Min'}[kind],result,item)
                if kind=='average':result=graph.binary('Div',result,graph.const(len(inputs),dtype=np.float32))
                graph.node('Identity',[result],outs)
            elif kind in ('scale','bias'):
                result=ins[0]
                if kind=='scale':shape=list(p.shapeScale);value=nn_weights(p.scale,shape);value=value.reshape(shape+[1,1]) if len(shape)==1 else value;result=graph.binary('Mul',result,graph.const(value))
                if kind=='bias' or p.hasBias:shape=list(p.shape if kind=='bias' else p.shapeBias);value=nn_weights(p.bias,shape);value=value.reshape(shape+[1,1]) if len(shape)==1 else value;result=graph.binary('Add',result,graph.const(value))
                graph.node('Identity',[result],outs)
            elif kind=='batchnorm':
                c=p.channels;shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3,'batch norm requires known rank');channel_axis=len(shape)-3;broadcast=[1]*len(shape);broadcast[channel_axis]=c
                if p.computeMeanVar:
                    axes=list(range(channel_axis+1,len(shape))) if p.instanceNormalization else [a for a in range(len(shape)) if a!=channel_axis];graph.normalize(ins[0],outs,axes,p.epsilon or 1e-5,graph.const(nn_weights(p.gamma,[c]).reshape(broadcast)),graph.const(nn_weights(p.beta,[c]).reshape(broadcast)))
                else:
                    gamma,beta,mean,variance=[graph.const(nn_weights(value,[c]).reshape(broadcast)) for value in (p.gamma,p.beta,p.mean,p.variance)];normalized=graph.binary('Div',graph.binary('Sub',ins[0],mean),graph.node('Sqrt',[graph.binary('Add',variance,graph.const(p.epsilon or 1e-5,dtype=np.float32))]));graph.node('Add',[graph.binary('Mul',normalized,gamma),beta],outs)
            elif kind=='mvn':
                axes=[-3,-2,-1] if p.acrossChannels else [-2,-1]
                if p.normalizeVariance:graph.normalize(ins[0],outs,axes,p.epsilon or 1e-6)
                else:graph.node('Sub',[ins[0],graph.node('ReduceMean',ins,axes=axes,keepdims=1)],outs)
            elif kind=='l2normalize':
                summed=graph.node('ReduceSum',[graph.binary('Mul',ins[0],ins[0]),graph.const([-3,-2,-1],dtype=np.int64)],keepdims=1);graph.node('Div',[ins[0],graph.node('Sqrt',[graph.binary('Add',summed,graph.const(p.epsilon or 1e-6,dtype=np.float32))])],outs)
            elif kind=='lrn':
                image,prefix=graph.spatial_input(ins[0]);result=graph.node('LRN',[image],size=p.localSize,alpha=p.alpha,beta=p.beta,bias=p.k);graph.spatial_output(result,prefix,outs)
            elif kind=='innerProduct':
                shape=graph.shapes.get(ins[0]);require(shape and 1<=len(shape)<=5,'inner product requires rank 1..5');flat=graph.node('Reshape',[ins[0],graph.const([-1,p.inputChannels],dtype=np.int64)]);weight=graph.const(nn_weights(p.weights,[p.outputChannels,p.inputChannels]));inputs=[flat,weight]
                if p.hasBias:inputs.append(graph.const(nn_weights(p.bias,[p.outputChannels])))
                target=shape[:-1]+[p.outputChannels] if len(shape)<4 else shape[:-3]+[p.outputChannels,1,1];product=graph.node('Gemm',inputs,transB=1);graph.node('Reshape',[product,graph.const(target,dtype=np.int64)],outs)
            elif kind in ('simpleRecurrent','gru','uniDirectionalLSTM','biDirectionalLSTM'):lower_nn_recurrent(graph,kind,p,ins,outs)
            elif kind=='batchedMatmul':
                weighted=len(ins)==1;left=ins[0];right=graph.const(nn_weights(p.weights,[p.weightMatrixSecondDimension,p.weightMatrixFirstDimension]).T) if weighted else ins[1]
                def transpose_matrix(value):
                    rank=len(graph.shapes.get(value,[]));require(rank>=2,'matrix transpose requires known rank');perm=list(range(rank));perm[-2:]=perm[-2:][::-1];return graph.node('Transpose',[value],perm=perm)
                if p.transposeA and not weighted:left=transpose_matrix(left)
                if p.transposeB and not weighted:right=transpose_matrix(right)
                product=graph.node('MatMul',[left,right]);graph.node('Add',[product,graph.const(nn_weights(p.bias,[p.weightMatrixSecondDimension]))],outs) if p.hasBias and weighted else graph.node('Identity',[product],outs)
            elif kind=='pooling':
                image,prefix=graph.spatial_input(ins[0]);ins=[image];original_outputs=outs;outs=outs if prefix is None else [graph.unique('pooled')]
                require(p.type in (0,1,2),'unknown pooling type')
                if p.globalPooling:
                    if p.type==2:graph.node('Sqrt',[graph.node('ReduceSum',[graph.binary('Mul',ins[0],ins[0]),graph.const([-2,-1],dtype=np.int64)],keepdims=1)],outs)
                    else:graph.node('GlobalMaxPool' if p.type==0 else 'GlobalAveragePool',ins,outs)
                else:
                    kernel=list(p.kernelSize) or [3,3];stride=list(p.stride) or [1,1];attrs={'kernel_shape':kernel,'strides':stride};padding=p.WhichOneof('PoolingPaddingType')
                    if padding=='same':attrs['auto_pad']='SAME_LOWER' if p.same.asymmetryMode==1 else 'SAME_UPPER'
                    elif padding=='valid':border=p.valid.paddingAmounts.borderAmounts;attrs['pads']=[x.startEdgeSize for x in border]+[x.endEdgeSize for x in border] if border else [0]*4
                    elif padding=='includeLastPixel':amounts=list(p.includeLastPixel.paddingAmounts) or [0,0];require(len(amounts)==2,'includeLastPixel expects height/width padding');attrs.update(pads=amounts+amounts,ceil_mode=1)
                    else:raise Unsupported(f'unsupported pooling padding {padding}')
                    if p.type==1:attrs['count_include_pad']=int(not p.avgPoolExcludePadding)
                    if p.type==2 and attrs.get('ceil_mode'):
                        shape=graph.shapes.get(ins[0]);require(shape and len(shape)==4 and all(shape),'complete L2 pooling requires fixed NCHW dimensions');pads=list(attrs['pads']);after=[]
                        for size,kernel_dim,step,before in zip(shape[-2:],kernel,stride,pads[:2]):
                            count=math.ceil((size+2*before-kernel_dim)/step)+1
                            if before>0 and (count-1)*step>=size+before:count-=1
                            after.append(max(before,(count-1)*step+kernel_dim-size-before))
                        padded=graph.node('Pad',[ins[0],graph.const([0,0]+pads[:2]+[0,0]+after,dtype=np.int64),graph.const(0,dtype=np.float32)]);squared=graph.binary('Mul',padded,padded);averaged=graph.node('AveragePool',[squared],count_include_pad=1,kernel_shape=kernel,strides=stride);graph.node('Sqrt',[graph.binary('Mul',averaged,graph.const(math.prod(kernel),dtype=np.float32))],outs)
                    else:
                        if p.type==2:attrs['p']=2
                        graph.node('MaxPool' if p.type==0 else 'AveragePool' if p.type==1 else 'LpPool',ins,outs,**attrs)
                if prefix is not None:graph.spatial_output(outs[0],prefix,original_outputs)
            elif kind=='upsample':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3,'upsample requires known spatial rank');graph.resize(ins[0],outs,[1]*(len(shape)-2)+(list(p.fractionalScalingFactor or p.scalingFactor) or [1,1]),mode='nearest' if p.mode==0 else 'linear',coordinates='asymmetric' if p.mode==0 or p.linearUpsampleMode==0 else 'align_corners' if p.linearUpsampleMode==1 else 'half_pixel')
            elif kind=='resizeBilinear':
                image,prefix=graph.spatial_input(ins[0]);ins=[image];original_outputs=outs;outs=outs if prefix is None else [graph.unique('resized')];shape=graph.shapes[image];mode=p.mode.samplingMethod;require(mode in (0,1,2,3),'unknown bilinear sampling method');sizes=shape[:2]+(list(p.targetSize) or [1,1])
                if mode==3:graph.sampled_resize(ins[0],outs,sizes,'OFFSET_CORNERS')
                elif mode==1 and 1 in sizes[-2:]:graph.sampled_resize(ins[0],outs,sizes,'ALIGN_CORNERS')
                else:graph.resize(ins[0],outs,sizes=sizes,mode='linear',coordinates={0:'align_corners',1:'align_corners',2:'asymmetric'}[mode])
                if prefix is not None:graph.spatial_output(outs[0],prefix,original_outputs)
            elif kind=='cropResize':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)==5 and shape[0]==1,'NN crop resize feature map must have shape 1,B,C,H,W');image=graph.node('Squeeze',[ins[0],graph.const([0],dtype=np.int64)]);graph.shapes[image]=shape[1:];graph.crop_resize(image,ins[1],outs,*(list(p.targetSize) or [1,1]),p.normalizedCoordinates,p.spatialScale,{0:'CORNERS_HEIGHT_FIRST',1:'CORNERS_WIDTH_FIRST',2:'CENTER_SIZE_HEIGHT_FIRST',3:'CENTER_SIZE_WIDTH_FIRST'}[p.boxIndicesMode.boxMode],{0:'STRICT_ALIGN_CORNERS',1:'ALIGN_CORNERS',2:'DEFAULT',3:'OFFSET_CORNERS'}[p.mode.samplingMethod])
            elif kind=='NonMaximumSuppression':
                graph.nms(ins[0],ins[1],outs,ins[2] if len(ins)>2 else graph.const(p.iouThreshold,dtype=np.float32),ins[3] if len(ins)>3 else graph.const(p.scoreThreshold,dtype=np.float32),ins[4] if len(ins)>4 else graph.const(p.maxBoxes,dtype=np.int64),p.perClassSuppression)
            elif kind=='reorganizeData':
                require(p.mode in (0,1,2),'unknown data reorganization');image,prefix=graph.spatial_input(ins[0]);result=graph.node('DepthToSpace' if p.mode in (1,2) else 'SpaceToDepth',[image],blocksize=p.blockSize,**({'mode':'CRD' if p.mode==2 else 'DCR'} if p.mode in (1,2) else {}));graph.spatial_output(result,prefix,outs)
            elif kind=='concat':graph.node('Concat',ins,outs,axis=-3 if not p.sequenceConcat else -5)
            elif kind=='concatND':
                if p.interleave:
                    shape=graph.shapes.get(ins[0]);require(shape and all(shape) and all(graph.shapes.get(x)==shape for x in ins),'interleaved concat requires equal fixed shapes');axis=p.axis%len(shape);joined=graph.node('Concat',[graph.node('Unsqueeze',[x,graph.const([axis+1],dtype=np.int64)]) for x in ins],axis=axis+1);target=list(shape);target[axis]*=len(ins);graph.node('Reshape',[joined,graph.const(target,dtype=np.int64)],outs)
                else:graph.node('Concat',ins,outs,axis=p.axis)
            elif kind=='stack':graph.node('Concat',[graph.node('Unsqueeze',[value,graph.const([p.axis],dtype=np.int64)]) for value in ins],outs,axis=p.axis)
            elif kind in ('split','splitND'):
                axis=-3 if kind=='split' else p.axis;inputs=ins+[graph.const(list(p.splitSizes),dtype=np.int64)] if kind=='splitND' and p.splitSizes else ins;graph.node('Split',inputs,outs,axis=axis)
            elif kind in ('permute','transpose'):
                perm=list(p.axis if kind=='permute' else p.axes)
                if kind=='permute':require(len(graph.shapes.get(ins[0],[]))==5,'legacy permute requires rank 5');perm=perm or [0,1,2,3];require(sorted(perm)==[0,1,2,3],'legacy permute axes must reorder Seq,C,H,W');mapped=[0,2,3,4];perm=[mapped[perm[0]],1]+[mapped[axis] for axis in perm[1:]]
                graph.node('Transpose',ins,outs,perm=perm)
            elif kind in ('reshape','reshapeStatic','rankPreservingReshape'):
                target=list(p.targetShape);x=ins[0];current=graph.shapes.get(x)
                if kind=='reshape' and len(target)==3 and current:target=current[:-3]+target
                elif kind=='reshape' and len(target)==4 and current and len(current)==5:target=target[:1]+[current[1]]+target[1:]
                if kind=='reshape' and p.mode==1:
                    require(current and len(current)>=3 and len(target)>=3,'channel-last reshape requires known rank');perm=list(range(len(current)));perm[-3:]=[perm[-2],perm[-1],perm[-3]];x=graph.node('Transpose',[x],perm=perm);last_shape=target[:-3]+[target[-2],target[-1],target[-3]];shaped=graph.node('Reshape',[x,graph.const(last_shape,dtype=np.int64)]);perm=list(range(len(target)));perm[-3:]=[perm[-1],perm[-3],perm[-2]];graph.node('Transpose',[shaped],outs,perm=perm)
                else:graph.node('Reshape',[x,graph.const(target,dtype=np.int64)],outs)
            elif kind=='reshapeDynamic':graph.node('Reshape',[ins[0],graph.node('Cast',[ins[1]],to=T.INT64)],outs)
            elif kind in ('flatten','flattenTo2D'):
                if kind=='flattenTo2D':graph.node('Flatten',ins,outs,axis=p.axis)
                else:
                    shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3 and all(shape),'legacy flatten requires fixed rank');x=ins[0]
                    if p.mode==1:perm=list(range(len(shape)));perm[-3:]=[perm[-2],perm[-1],perm[-3]];x=graph.node('Transpose',[x],perm=perm)
                    graph.node('Reshape',[x,graph.const(shape[:-3]+[math.prod(shape[-3:]),1,1],dtype=np.int64)],outs)
            elif kind in ('squeeze','expandDims'):
                axes=list(p.axes)
                if kind=='squeeze' and p.squeezeAll:
                    shape=graph.shapes.get(ins[0]);require(shape is not None and all(x is not None for x in shape),'NN squeezeAll requires fixed singleton axes');axes=[i for i,size in enumerate(shape) if size==1];axes=axes[:-1] if len(axes)==len(shape) else axes
                graph.node('Squeeze' if kind=='squeeze' else 'Unsqueeze',ins+([graph.const(axes,dtype=np.int64)] if axes else [graph.const([],dtype=np.int64)] if kind=='squeeze' else []),outs)
            elif kind=='clip':graph.node('Clip',ins+[graph.const(p.minVal,dtype=np.float32),graph.const(p.maxVal,dtype=np.float32)],outs)
            elif kind in ('loadConstant','loadConstantND'):
                value=nn_weights(p.data,list(p.shape));graph.const(value.reshape([1,1]+list(p.shape)) if kind=='loadConstant' else value,outs[0])
            elif kind in ('softmax','softmaxND'):graph.node('Softmax',ins,outs,axis=p.axis if kind=='softmaxND' else -3)
            elif kind=='padding':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=2,'spatial padding requires known rank');border=p.paddingAmounts.borderAmounts;require(len(border)==2,'spatial padding must provide height/width amounts');pads=[0]*(len(shape)-2)+[x.startEdgeSize for x in border]+[0]*(len(shape)-2)+[x.endEdgeSize for x in border];mode=p.WhichOneof('PaddingType');graph.node('Pad',ins+[graph.const(pads,dtype=np.int64),graph.const(p.constant.value if mode=='constant' else 0,dtype=np.float32)],outs,mode={'constant':'constant','reflection':'reflect','replication':'edge'}[mode])
            elif kind=='getShape':graph.node('Cast',[graph.node('Shape',ins)],outs,to=T.FLOAT)
            elif kind=='tile':
                rank=len(graph.shapes.get(ins[0],[]));reps=graph.node('Cast',[ins[1]],to=T.INT64) if len(ins)>1 else graph.const([1]*(rank-len(p.reps))+list(p.reps),dtype=np.int64);graph.node('Tile',[ins[0],reps],outs)
            elif kind=='gather':graph.node('Gather',[ins[0],graph.node('Cast',[ins[1]],to=T.INT64)],outs,axis=p.axis)
            elif kind=='gatherND':graph.node('GatherND',[ins[0],graph.node('Cast',[ins[1]],to=T.INT64)],outs)
            elif kind=='gatherAlongAxis':graph.node('GatherElements',[ins[0],graph.node('Cast',[ins[1]],to=T.INT64)],outs,axis=p.axis)
            elif kind in ('scatter','scatterND','scatterAlongAxis'):graph.scatter(ins[0],ins[1],ins[2],outs,{'scatter':'scatter','scatterND':'scatter_nd','scatterAlongAxis':'scatter_along_axis'}[kind],p.axis if kind!='scatterND' else 0,['update','add','sub','mul','div','max','min'][p.mode])
            elif kind in ('sliceStatic','sliceDynamic'):
                shape=graph.shapes.get(ins[0]);require(shape,'NN slice requires known rank');rank=len(shape);begin=graph.const(list(p.beginIds),dtype=np.int64) if kind=='sliceStatic' else ins[1];end=ins[2] if kind=='sliceDynamic' and len(ins)>2 else graph.const(list(p.endIds),dtype=np.int64);strides=ins[3] if kind=='sliceDynamic' and len(ins)>3 else None if not p.strides else list(p.strides);bm=ins[4] if kind=='sliceDynamic' and len(ins)>4 else None if not p.beginMasks else list(p.beginMasks);em=ins[5] if kind=='sliceDynamic' and len(ins)>5 else None if not p.endMasks else list(p.endMasks);sq=ins[6] if kind=='sliceDynamic' and len(ins)>6 else None if not p.squeezeMasks else list(p.squeezeMasks);graph.slice(ins[0],begin,end,outs,strides,bm,em,sq)
            elif kind=='slice':graph.node('Slice',[ins[0],graph.const([p.startIndex],dtype=np.int64),graph.const([p.endIndex],dtype=np.int64),graph.const([int(p.axis)-3],dtype=np.int64),graph.const([p.stride],dtype=np.int64)],outs)
            elif kind=='sliceBySize':
                begin=graph.node('Cast',[ins[1]],to=T.INT64);end=graph.binary('Add',begin,graph.const(p.size,dtype=np.int64));graph.node('Slice',[ins[0],graph.node('Reshape',[begin,graph.const([1],dtype=np.int64)]),graph.node('Reshape',[end,graph.const([1],dtype=np.int64)]),graph.const([p.axis],dtype=np.int64)],outs)
            elif kind in ('embedding','embeddingND'):
                shape=graph.shapes.get(ins[0]);require(shape and all(shape),'embedding requires fixed shape');legacy=kind=='embedding';channels=p.outputChannels if legacy else p.embeddingSize;vocab=p.inputDim if legacy else p.vocabSize;weights=nn_weights(p.weights,[channels,vocab]).T;indices=graph.node('Cast',[graph.node('Reshape',[ins[0],graph.const([-1],dtype=np.int64)])],to=T.INT64);result=graph.node('Gather',[graph.const(weights),indices],axis=0)
                if p.hasBias:result=graph.binary('Add',result,graph.const(nn_weights(p.bias,[channels])))
                target=shape[:-3]+[channels,1,1] if legacy else shape[:-1]+[channels];graph.node('Reshape',[result,graph.const(target,dtype=np.int64)],outs)
            elif kind=='oneHot':graph.node('OneHot',[graph.node('Cast',[ins[0]],to=T.INT64),graph.node('Cast',[ins[1]],to=T.INT64) if len(ins)>1 else graph.const(p.oneHotVectorSize,dtype=np.int64),graph.const([p.offValue,p.onValue],dtype=np.float32)],outs,axis=p.axis)
            elif kind=='whereNonZero':graph.node('Cast',[graph.node('Transpose',[graph.node('NonZero',ins)],perm=[1,0])],outs,to=T.FLOAT)
            elif kind=='argSort':
                shape=graph.shapes.get(ins[0]);require(shape and shape[p.axis],'argSort requires fixed axis length');index=graph.unique('sort_indices');graph.node('TopK',ins+[graph.const([shape[p.axis]],dtype=np.int64)],[graph.unique('sort_values'),index],axis=p.axis,largest=int(p.descending),sorted=1);graph.node('Cast',[index],outs,to=T.FLOAT)
            elif kind=='reverseSeq':graph.node('ReverseSequence',[ins[0],graph.node('Cast',[ins[1]],to=T.INT64)],outs,batch_axis=p.batchAxis,time_axis=p.sequenceAxis)
            elif kind=='matrixBandPart':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=2 and all(shape[-2:]),'matrix band requires fixed dimensions');row,col=np.indices(shape[-2:]);mask=((p.numLower<0)|(row-col<=p.numLower))&((p.numUpper<0)|(col-row<=p.numUpper));graph.node('Where',[graph.const(mask),ins[0],graph.const(0,dtype=np.float32)],outs)
            elif kind in ('lowerTriangular','upperTriangular'):
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=2 and all(shape[-2:]),'triangular matrix requires fixed dimensions');mask=np.tri(*shape[-2:],k=p.k,dtype=bool) if kind=='lowerTriangular' else ~np.tri(*shape[-2:],k=p.k-1,dtype=bool);graph.node('Where',[graph.const(mask),ins[0],graph.const(0,dtype=np.float32)],outs)
            elif kind=='slidingWindows':
                shape=graph.shapes.get(ins[0]);require(shape and shape[p.axis] and p.windowSize>0 and p.step>0,'sliding windows requires valid fixed axis');indices=np.arange(0,shape[p.axis]-p.windowSize+1,p.step).reshape(-1,1)+np.arange(p.windowSize);graph.node('Gather',[ins[0],graph.const(indices,dtype=np.int64)],outs,axis=p.axis)
            elif kind.startswith(('randomNormal','randomUniform','randomBernoulli')):
                shape=graph.node('Shape',ins) if kind.endswith('Like') else graph.const(list(p.outputShape),dtype=np.int64) if kind.endswith('Static') else graph.node('Cast',ins,to=T.INT64);distribution='normal' if kind.startswith('randomNormal') else 'uniform' if kind.startswith('randomUniform') else 'bernoulli';graph.random(distribution,shape,outs,p.seed,getattr(p,'mean',0),getattr(p,'stdDev',1),getattr(p,'minVal',0),getattr(p,'maxVal',1),getattr(p,'prob',0.5))
            elif kind=='categoricalDistribution':
                shape=graph.shapes.get(ins[0]);require(shape and all(shape),'categorical distribution requires fixed shape');values=graph.node('Reshape',[ins[0],graph.const([-1,shape[-1]],dtype=np.int64)]);logits=values if p.isLogits else graph.node('Log',[graph.binary('Add',values,graph.const(p.eps,dtype=np.float32))]);logits=graph.binary('Div',logits,graph.const(p.temperature,dtype=np.float32));sampled=graph.node('Cast',[graph.node('Multinomial',[logits],seed=float(p.seed),sample_size=p.numSamples,dtype=T.INT32)],to=T.FLOAT);graph.node('Reshape',[sampled,graph.const(shape[:-1]+[p.numSamples],dtype=np.int64)],outs)
            elif kind=='sequenceRepeat':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=5,'sequenceRepeat requires rank at least 5');repeats=[1]*len(shape);repeats[-5]=p.nRepetitions or 1;graph.node('Tile',ins+[graph.const(repeats,dtype=np.int64)],outs)
            elif kind=='dot':
                require(len(ins)==2,'dot requires two tensors');axes=graph.const([-3],dtype=np.int64);product=graph.node('ReduceSum',[graph.binary('Mul',ins[0],ins[1]),axes],keepdims=1)
                if p.cosineSimilarity:
                    norms=[graph.node('Sqrt',[graph.node('ReduceSum',[graph.binary('Mul',value,value),axes],keepdims=1)]) for value in ins];product=graph.binary('Div',product,graph.binary('Mul',*norms))
                graph.node('Identity',[product],outs)
            elif kind=='reduce':
                axes={0:[-3,-2,-1],1:[-2,-1],2:[-3],3:[-2],4:[-1]}[p.axis];mapping={0:'ReduceSum',1:'ReduceMean',2:'ReduceProd',4:'ReduceSumSquare',5:'ReduceL1',6:'ReduceL2',7:'ReduceMax',8:'ReduceMin'}
                if p.mode==9:require(len(axes)==1,'legacy ArgMax only permits one axis');graph.node('Cast',[graph.node('ArgMax',ins,axis=axes[0],keepdims=1)],outs,to=T.FLOAT)
                elif p.mode==3:
                    total=graph.node('ReduceSum',ins+[graph.const(axes,dtype=np.int64)],keepdims=1);graph.node('Log',[graph.binary('Add',total,graph.const(p.epsilon,dtype=np.float32))],outs)
                else:
                    attrs={'keepdims':1};inputs=ins+[graph.const(axes,dtype=np.int64)] if p.mode==0 else ins
                    if p.mode!=0:attrs['axes']=axes
                    graph.node(mapping[p.mode],inputs,outs,**attrs)
            elif kind in ('rangeStatic','rangeDynamic'):
                end=graph.const(p.endValue,dtype=np.float32) if kind=='rangeStatic' else graph.node('Reshape',[ins[0],graph.const([],dtype=np.int64)]);start=graph.node('Reshape',[ins[1],graph.const([],dtype=np.int64)]) if kind=='rangeDynamic' and len(ins)>1 else graph.const(p.startValue,dtype=np.float32);step=graph.node('Reshape',[ins[2],graph.const([],dtype=np.int64)]) if kind=='rangeDynamic' and len(ins)>2 else graph.const(p.stepSizeValue,dtype=np.float32);graph.node('Range',[start,end,step],outs)
            elif kind=='constantPad':
                rank=len(graph.shapes[ins[0]]);pad=graph.node('Cast',[ins[1]],to=T.INT64) if len(ins)>1 else graph.const(list(p.padAmounts),dtype=np.int64);shaped=graph.node('Reshape',[pad,graph.const([rank,2],dtype=np.int64)]);before=graph.node('Gather',[shaped,graph.const(0,dtype=np.int64)],axis=1);after=graph.node('Gather',[shaped,graph.const(1,dtype=np.int64)],axis=1)
                if p.padToGivenOutputSizeMode:
                    current=graph.node('Shape',[ins[0]]);zero=graph.const(0,dtype=np.int64);before=graph.binary('Max',graph.binary('Sub',before,current),zero);after=graph.binary('Max',graph.binary('Sub',after,current),zero)
                graph.node('Pad',[ins[0],graph.node('Concat',[before,after],axis=0),graph.const(p.value,dtype=np.float32)],outs)
            elif kind=='crop':
                shape=graph.shapes.get(ins[0]);require(shape and len(shape)>=3,'crop requires known input shape')
                if len(ins)==2:target=graph.shapes.get(ins[1]);require(target and len(p.offset)==2,'reference crop requires known target and two offsets');begin=list(p.offset);end=[b+s for b,s in zip(begin,target[-2:])]
                else:border=p.cropAmounts.borderAmounts;require(len(border)==2,'crop amounts must contain H/W');begin=[b.startEdgeSize for b in border];end=[s-b.endEdgeSize for s,b in zip(shape[-2:],border)]
                graph.node('Slice',[ins[0],graph.const(begin,dtype=np.int64),graph.const(end,dtype=np.int64),graph.const([-2,-1],dtype=np.int64)],outs)
            elif kind=='reverse':
                axes=[i for i,enabled in enumerate(p.reverseDim) if enabled];graph.node('Slice',ins+[graph.const([-1]*len(axes),dtype=np.int64),graph.const([np.iinfo(np.int64).min]*len(axes),dtype=np.int64),graph.const(axes,dtype=np.int64),graph.const([-1]*len(axes),dtype=np.int64)],outs)
            elif kind in ('argMax','argMin'):graph.node('Cast',[graph.node('ArgMax' if kind=='argMax' else 'ArgMin',ins,axis=p.axis,keepdims=int(not p.removeDim or len(graph.shapes.get(ins[0],[]))==1))],outs,to=T.FLOAT)
            elif kind=='topK':
                require(len(outs)==2,'topK requires value/index outputs');index=graph.unique('topk_indices');k=graph.node('Reshape',[graph.node('Cast',[ins[1]],to=T.INT64),graph.const([1],dtype=np.int64)]) if len(ins)>1 else graph.const([p.K],dtype=np.int64);graph.node('TopK',[ins[0],k],[outs[0],index],axis=p.axis,largest=int(not p.useBottomK),sorted=1);graph.node('Cast',[index],[outs[1]],to=T.FLOAT)
            elif kind=='cumSum':
                axis=graph.node('Reshape',[graph.node('Cast',[ins[1]],to=T.INT64),graph.const([],dtype=np.int64)]) if len(ins)>1 else graph.const(p.axis,dtype=np.int64);graph.node('CumSum',[ins[0],axis],outs,exclusive=int(p.excludeFinalSum),reverse=int(p.reverse))
            elif kind in ('fillLike','fillStatic','fillDynamic'):
                shape=graph.const(list(p.targetShape),dtype=np.int64) if kind=='fillStatic' else graph.node('Shape',ins) if kind=='fillLike' else graph.node('Cast',ins,to=T.INT64);graph.node('ConstantOfShape',[shape],outs,value=NH.from_array(np.array([p.value],dtype=np.float32)))
            elif kind in ('broadcastToLike','broadcastToStatic','broadcastToDynamic'):
                shape=graph.const(list(p.targetShape),dtype=np.int64) if kind=='broadcastToStatic' else graph.node('Shape',[ins[1]]) if kind=='broadcastToLike' else graph.node('Cast',[ins[1]],to=T.INT64);graph.node('Expand',[ins[0],shape],outs)
            elif kind=='reshapeLike':graph.node('Reshape',[ins[0],graph.node('Shape',[ins[1]])],outs)
            elif kind=='whereBroadcastable':graph.node('Where',[graph.node('Cast',[ins[0]],to=T.BOOL)]+ins[1:],outs)
            elif kind=='layerNormalization':graph.normalize(ins[0],outs,list(range(-len(p.normalizedShape),0)),p.eps,graph.const(nn_weights(p.gamma,list(p.normalizedShape))),graph.const(nn_weights(p.beta,list(p.normalizedShape))))
            elif kind.startswith('reduce') and kind!='reduce':
                inputs=ins;attrs={'keepdims':int(p.keepDims)}
                if kind=='reduceSum':inputs=inputs+[graph.const(list(p.axes),dtype=np.int64)] if p.axes and not p.reduceAll else inputs
                elif p.axes and not p.reduceAll:attrs['axes']=list(p.axes)
                mapping={'reduceL1':'ReduceL1','reduceL2':'ReduceL2','reduceMax':'ReduceMax','reduceMin':'ReduceMin','reduceSum':'ReduceSum','reduceProd':'ReduceProd','reduceMean':'ReduceMean','reduceLogSum':'ReduceLogSum','reduceSumSquare':'ReduceSumSquare','reduceLogSumExp':'ReduceLogSumExp'};require(kind in mapping,f'unsupported reduction {kind}')
                shape=graph.shapes.get(ins[0]);rank=len(shape) if shape is not None else None;all_reduced=p.reduceAll or not p.axes or bool(rank and len({axis%rank for axis in p.axes})==rank)
                # Core ML NN keeps a one-element rank-one tensor after reducing
                # every dimension; ONNX otherwise returns a scalar tensor.
                if not p.keepDims and all_reduced:graph.node('Reshape',[graph.node(mapping[kind],inputs,**attrs),graph.const([1],dtype=np.int64)],outs)
                else:graph.node(mapping[kind],inputs,outs,**attrs)
            else:raise Unsupported(f'NN operator {kind} has no verified lowering')
        except Exception as error:graph.diagnose(layer.name,kind,ins,outs,error)
    if return_aliases:return aliases
    results=[]
    for feature in spec.description.output:
        value=aliases.get(feature.name,feature.name);shape=graph.shapes.get(value);kind=feature.type.WhichOneof('Type')
        if shape and len(shape)==5 and shape[:2]==[1,1] and (kind=='imageType' or kind=='multiArrayType' and len(feature.type.multiArrayType.shape)==3):value=graph.node('Reshape',[value,graph.const([1]+shape[-3:],dtype=np.int64)])
        results.append(value)
    return results

def nn_assigned(network):
    result=set()
    for layer in network.layers:
        result.update(layer.output);kind=layer.WhichOneof('layer')
        if kind=='branch':result.update(nn_assigned(layer.branch.ifBranch));result.update(nn_assigned(layer.branch.elseBranch))
        elif kind=='loop':result.update(nn_assigned(layer.loop.bodyNetwork));result.update(nn_assigned(layer.loop.conditionNetwork))
    return result

def nn_condition(graph,value):
    shape=graph.shapes.get(value);require(shape is None or all(x is not None for x in shape) and math.prod(shape)==1,'NN condition must contain exactly one value');return graph.node('Reshape',[graph.node('Cast',[value],to=T.BOOL),graph.const([],dtype=np.int64)])

def nn_network(graph,network,aliases,control=False):
    spec=Model_pb2.Model();spec.specificationVersion=4;spec.neuralNetwork.CopyFrom(network);return lower_nn(graph,spec,aliases,return_aliases=True,control=control)

def nn_conditional(graph,condition,true_network,false_network,aliases,control=False,assume_active=False):
    true_assigned=nn_assigned(true_network);false_assigned=nn_assigned(false_network) if false_network is not None else set();assigned=sorted((true_assigned|false_assigned).intersection(set(aliases)|true_assigned.intersection(false_assigned))|({'@break','@continue'} if control else set()));branches=[];known_shapes={};known_types={}
    for branch_index,network in enumerate((true_network,false_network)):
        child=Graph();child.shapes=dict(graph.shapes);child.types=dict(graph.types);child.constants=dict(graph.constants);child.reserved=set(graph.reserved)|set(graph.shapes)|set(graph.constants);bound=dict(aliases)
        for original,value in bound.items():child.shapes[original]=child.shapes.get(value);child.types[original]=child.types.get(value,'FLOAT32')
        if control and assume_active and branch_index==0:
            for key in ('@break','@continue'):bound[key]=child.const(False)
        changed=nn_network(child,network,bound,control) if network is not None else bound;infos=[]
        for original in assigned:
            require(original in changed,f'conditional output {original} is undefined in one branch');value=changed[original];child.initializer(value);alias=child.unique('branch_result');child.node('Identity',[value],[alias]);shape=child.shapes.get(value);dtype=child.types.get(value,'BOOL' if original.startswith('@') else 'FLOAT32');known_shapes.setdefault(original,shape);known_types.setdefault(original,dtype);infos.append(H.make_tensor_value_info(alias,tensor_dtype(dtype),shape))
        branches.append(H.make_graph(child.nodes,f'nn_branch_{branch_index}',[],infos,list(child.initializers.values())));graph.inventory.extend(dict(row,block=f'nn_branch_{branch_index}') for row in child.inventory);graph.errors.extend(dict(row,block=f'nn_branch_{branch_index}') for row in child.errors)
    # A validated pure branch with no externally visible assignments is dead code.
    if not assigned:return dict(aliases)
    outputs=[graph.unique(original.replace('@','control_')) for original in assigned];graph.node('If',[condition],outputs,then_branch=branches[0],else_branch=branches[1]);updated=dict(aliases)
    for name,value in zip(assigned,outputs):updated[name]=value;graph.shapes[value]=known_shapes[name];graph.types[value]=known_types[name]
    return updated

def nn_loop(graph,params,inputs,aliases):
    if params.conditionNetwork.layers:aliases=nn_network(graph,params.conditionNetwork,aliases)
    assigned=sorted((nn_assigned(params.bodyNetwork)|nn_assigned(params.conditionNetwork)).intersection(aliases));condition=nn_condition(graph,aliases[params.conditionVar]) if params.conditionVar else graph.const(True);iterations=graph.node('Reshape',[graph.node('Cast',[inputs[0]],to=T.INT64),graph.const([],dtype=np.int64)]) if inputs else graph.const(params.maxLoopIterations,dtype=np.int64) if params.maxLoopIterations>0 else '';child=Graph();child.shapes=dict(graph.shapes);child.types=dict(graph.types);child.constants=dict(graph.constants);child.reserved=set(graph.reserved)|set(graph.shapes)|set(graph.constants);iteration=child.unique('iteration');condition_input=child.unique('condition');bound=dict(aliases);infos=[H.make_tensor_value_info(iteration,T.INT64,[]),H.make_tensor_value_info(condition_input,T.BOOL,[])];values=[]
    for name in assigned:
        source=aliases[name];parameter=child.unique('loop_value');bound[name]=parameter;child.shapes[parameter]=graph.shapes.get(source);child.types[parameter]=graph.types.get(source,'FLOAT32');infos.append(H.make_tensor_value_info(parameter,tensor_dtype(child.types[parameter]),child.shapes[parameter]));values.append(source)
    for name in ('@break','@continue'):bound[name]=child.const(False);child.types[bound[name]]='BOOL';child.shapes[bound[name]]=[]
    changed=nn_network(child,params.bodyNetwork,bound,control=True);no_break=child.node('Not',[changed['@break']])
    if params.conditionNetwork.layers:changed=nn_conditional(child,no_break,params.conditionNetwork,None,changed)
    next_condition=nn_condition(child,changed[params.conditionVar]) if params.conditionVar else child.const(True);next_condition=child.node('And',[next_condition,no_break]);outinfos=[H.make_tensor_value_info(next_condition,T.BOOL,[])];loop_results=[]
    for name in assigned:
        source=changed[name];child.initializer(source);result=child.unique('loop_result');child.node('Identity',[source],[result]);loop_results.append(result);outinfos.append(H.make_tensor_value_info(result,tensor_dtype(child.types.get(source,'FLOAT32')),child.shapes.get(source)))
    created=sorted((nn_assigned(params.bodyNetwork)|nn_assigned(params.conditionNetwork)).intersection(changed).difference(aliases));scan_outputs=[]
    for name in created:
        source=changed[name];child.initializer(source);result=child.unique('loop_created');child.node('Identity',[source],[result]);outinfos.append(H.make_tensor_value_info(result,tensor_dtype(child.types.get(source,'FLOAT32')),child.shapes.get(source)));scan_outputs.append(graph.unique('loop_scan'))
    # Fully validate even a loop with no observable tensor assignments. ONNX
    # Loop needs outputs; a pure finite loop can be eliminated after validation.
    if not assigned and not created:
        graph.inventory.extend(dict(row,block='nn_loop') for row in child.inventory);graph.errors.extend(dict(row,block='nn_loop') for row in child.errors);return dict(aliases)
    body=H.make_graph(child.nodes,'nn_loop',infos,outinfos,list(child.initializers.values()));outputs=[graph.unique('loop_'+name) for name in assigned];graph.node('Loop',[iterations,condition]+values,outputs+scan_outputs,body=body);updated=dict(aliases)
    for name,value in zip(assigned,outputs):updated[name]=value;graph.shapes[value]=graph.shapes.get(aliases[name]);graph.types[value]=graph.types.get(aliases[name],'FLOAT32')
    for name,value in zip(created,scan_outputs):updated[name]=graph.node('Gather',[value,graph.const(-1,dtype=np.int64)],axis=0);graph.shapes[updated[name]]=child.shapes.get(changed[name]);graph.types[updated[name]]=child.types.get(changed[name],'FLOAT32')
    graph.inventory.extend(dict(row,block='nn_loop') for row in child.inventory);graph.errors.extend(dict(row,block='nn_loop') for row in child.errors);return updated

def lower_nn_recurrent(graph,kind,p,inputs,outputs):
    shape=graph.shapes.get(inputs[0]);require(shape and len(shape)==5 and shape[-2:]==[1,1] and all(shape),'NN recurrent input must be fixed S,B,I,1,1');steps,batch,input_size=shape[:3];hidden=p.outputVectorSize;x=graph.node('Reshape',[inputs[0],graph.const([steps,batch,input_size],dtype=np.int64)]);directions=2 if kind=='biDirectionalLSTM' else 1;cell_kind='rnn' if kind=='simpleRecurrent' else 'gru' if kind=='gru' else 'lstm';sequence=p.sequenceOutput if cell_kind!='lstm' else p.params.sequenceOutput;results=[];states=[]
    def activation(value):
        name=value.WhichOneof('NonlinearityType');mapping={'ReLU':'relu','scaledTanh':'scaled_tanh','sigmoidHard':'hard_sigmoid'};parameter=getattr(value,name);return mapping.get(name,name),getattr(parameter,'alpha',None),getattr(parameter,'beta',None)
    for direction in range(directions):
        state_index=1+(2*direction if cell_kind=='lstm' else direction);initial_h=graph.node('Reshape',[inputs[state_index],graph.const([batch,hidden],dtype=np.int64)]) if len(inputs)>state_index else graph.const(np.zeros((batch,hidden),dtype=np.float32));initial_c=graph.node('Reshape',[inputs[state_index+1],graph.const([batch,hidden],dtype=np.int64)]) if cell_kind=='lstm' and len(inputs)>state_index+1 else graph.const(np.zeros((batch,hidden),dtype=np.float32)) if cell_kind=='lstm' else None;params=p.params if cell_kind=='lstm' else None
        if cell_kind=='rnn':weights=[nn_weights(p.weightMatrix,[hidden,input_size])];recursion=[nn_weights(p.recursionMatrix,[hidden,hidden])];bias=[nn_weights(p.biasVector,[hidden]) if p.hasBiasVector else np.zeros(hidden,dtype=np.float32)];acts=[activation(p.activation)]
        elif cell_kind=='gru':
            prefixes=['resetGate','outputGate','updateGate'];weights=[nn_weights(getattr(p,key+'WeightMatrix'),[hidden,input_size]) for key in prefixes];recursion=[nn_weights(getattr(p,key+'RecursionMatrix'),[hidden,hidden]) for key in prefixes];bias=[nn_weights(getattr(p,key+'BiasVector'),[hidden]) if p.hasBiasVectors else np.zeros(hidden,dtype=np.float32) for key in prefixes];require(len(p.activations)==2,'GRU requires two activations');acts=[activation(value) for value in p.activations]
        else:
            weights_p=p.weightParams[direction] if directions==2 else p.weightParams;prefixes=['inputGate','forgetGate','outputGate','blockInput'];weights=[nn_weights(getattr(weights_p,key+'WeightMatrix'),[hidden,input_size]) for key in prefixes];recursion=[nn_weights(getattr(weights_p,key+'RecursionMatrix'),[hidden,hidden]) for key in prefixes];bias=[nn_weights(getattr(weights_p,key+'BiasVector'),[hidden]) if params.hasBiasVectors else np.zeros(hidden,dtype=np.float32) for key in prefixes];selected=p.activationsForwardLSTM if directions==2 and direction==0 else p.activationsBackwardLSTM if directions==2 else p.activations;require(len(selected)==3,'LSTM requires three activations');acts=[activation(value) for value in selected]
        peephole=[nn_weights(getattr(weights_p,key+'PeepholeVector'),[hidden]) for key in prefixes[:3]] if cell_kind=='lstm' and params.hasPeepholeVectors else None;result,state,cell=graph.recurrent(x,initial_h,initial_c,cell_kind,weights,recursion,bias,acts,reverse=bool(direction==1 or getattr(p,'reverseInput',False)),sequence=sequence,preclip=50,cellclip=params.cellClipThreshold if params else None,peephole=peephole,coupled=params.coupledInputAndForgetGate if params else False,forget_bias=params.forgetBias if params else False)
        if direction and sequence:result=graph.node('Slice',[result,graph.const([-1],dtype=np.int64),graph.const([np.iinfo(np.int64).min],dtype=np.int64),graph.const([0],dtype=np.int64),graph.const([-1],dtype=np.int64)])
        results.append(result);states.append(state)
        if cell is not None:states.append(cell)
    combined=graph.node('Concat',results,axis=2) if directions==2 else results[0];graph.node('Reshape',[combined,graph.const([steps if sequence else 1,batch,hidden*directions,1,1],dtype=np.int64)],[outputs[0]])
    require(len(outputs)-1<=len(states),'NN recurrent state output count differs')
    for output,state in zip(outputs[1:],states):graph.node('Reshape',[state,graph.const([1,batch,hidden,1,1],dtype=np.int64)],[output])

def nn_preprocess(graph,spec,feature,current):
    network=getattr(spec,spec.WhichOneof('Type'));shape=graph.shapes[current]
    for item in network.preprocessing:
        if item.featureName not in ('',feature.name):continue
        kind=item.WhichOneof('preprocessor')
        if kind=='scaler':
            p=item.scaler;current=graph.binary('Mul',current,graph.const(p.channelScale,dtype=np.float32));bgr=feature.type.WhichOneof('Type')=='imageType' and feature.type.imageType.colorSpace==30
            bias=[p.blueBias,p.greenBias,p.redBias] if bgr else [p.redBias,p.greenBias,p.blueBias]
            require(len(shape)==4 and shape[1] in (1,3),'image scaler requires NCHW grayscale/RGB');current=graph.binary('Add',current,graph.const(np.asarray(bias if shape[1]==3 else [p.grayBias],dtype=np.float32).reshape(1,shape[1],1,1)))
        elif kind=='meanImage':
            mean=np.asarray(item.meanImage.meanImage,dtype=np.float32);require(len(shape)==4 and mean.size==math.prod(shape[1:]),f'mean image count {mean.size} differs from C/H/W {shape[1:]}')
            current=graph.binary('Sub',current,graph.const(mean.reshape([1]+shape[1:])))
        else:raise Unsupported(f'unknown NN input preprocessor {kind}')
    return current

def lower_pipeline(graph,spec,model_path):
    kind=spec.WhichOneof('Type');pipeline=getattr(spec,kind);pipeline=pipeline.pipeline if kind in ('pipelineClassifier','pipelineRegressor') else pipeline
    require(bool(pipeline.models),'pipeline has no models');available={f.name:f.name for f in spec.description.input}
    for index,child in enumerate(pipeline.models):
        prefix=f'pipeline_{index}_';stage=Graph();child_kind=child.WhichOneof('Type')
        try:
            for f in child.description.input:
                require(f.name in available,f'pipeline stage input {f.name} is not produced by an earlier model');shape=graph.shapes.get(available[f.name])
                stage.shapes[f.name]=list(shape) if shape else feature_shape(f)
            if child_kind in ('neuralNetwork','neuralNetworkClassifier','neuralNetworkRegressor'):
                aliases={}
                for f in child.description.input:
                    transformed=nn_preprocess(stage,child,f,f.name)
                    if transformed!=f.name:aliases[f.name]=transformed
                outputs=lower_nn(stage,child,aliases)
            elif child_kind=='mlProgram':outputs=lower_mil(stage,child,model_path)
            elif child_kind in ('pipeline','pipelineClassifier','pipelineRegressor'):outputs=lower_pipeline(stage,child,model_path)
            elif child_kind=='identity':
                require(len(child.description.input)==len(child.description.output),'identity input/output counts differ');outputs=[]
                for inp,out in zip(child.description.input,child.description.output):
                    if inp.name!=out.name:stage.node('Identity',[inp.name],[out.name])
                    outputs.append(out.name)
            else:raise Unsupported(f'pipeline model type {child_kind} has no verified lowering')
            require(len(outputs)==len(child.description.output),'pipeline stage result count differs from its declared outputs')
            for output_index,f in enumerate(child.description.output):
                if f.type.WhichOneof('Type')=='imageType' and index<len(pipeline.models)-1 and f.type.imageType.colorSpace!=40:
                    outputs[output_index]=stage.node('Floor',[stage.node('Clip',[outputs[output_index],stage.const(0,dtype=np.float32),stage.const(255,dtype=np.float32)])]);stage.inventory.append({'operator':'image_pipeline_buffer','precisionNote':'Intermediate 8-bit image buffers clip and truncate to UInt8. Apple image buffer rounding/color management is not asserted pixel-identical.'})
        except Exception as error:
            stage.diagnose(prefix,child_kind,[f.name for f in child.description.input],[f.name for f in child.description.output],error);outputs=[f.name for f in child.description.output]
        mapping={f.name:available[f.name] for f in child.description.input if f.name in available}
        def mapped(name):return mapping.get(name,prefix+name) if name else ''
        def rename_node(node):
            clone=onnx.NodeProto();clone.CopyFrom(node);clone.name=prefix+node.name
            for i,name in enumerate(clone.input):clone.input[i]=mapped(name)
            for i,name in enumerate(clone.output):clone.output[i]=mapped(name)
            for attribute in clone.attribute:
                for nested in ([attribute.g] if attribute.type==onnx.AttributeProto.GRAPH else attribute.graphs if attribute.type==onnx.AttributeProto.GRAPHS else []):
                    for value in list(nested.input)+list(nested.output)+list(nested.value_info):value.name=mapped(value.name)
                    for value in nested.initializer:value.name=mapped(value.name)
                    for value in nested.node:value.CopyFrom(rename_node(value))
            return clone
        for node in stage.nodes:
            graph.nodes.append(rename_node(node))
        for name,value in stage.initializers.items():
            clone=onnx.TensorProto();clone.CopyFrom(value);clone.name=mapped(name);graph.initializers[clone.name]=clone
        graph.constants.update({mapped(k):v for k,v in stage.constants.items()});graph.shapes.update({mapped(k):v for k,v in stage.shapes.items()});graph.types.update({mapped(k):v for k,v in stage.types.items()});graph.reserved.update(mapped(k) for k in stage.reserved)
        graph.inventory.extend(dict(row,pipelineStage=index) for row in stage.inventory)
        for error in stage.errors:
            entry=dict(error);entry['name']=prefix+entry['name'];entry['pipelineStage']=index;graph.errors.append(entry)
        for feature,result in zip(child.description.output,outputs):available[feature.name]=mapped(result)
    require(all(f.name in available for f in spec.description.output),'pipeline output is not produced by any stage')
    return [available[f.name] for f in spec.description.output]

def model_file(path,temporary,depth=0):
    require(depth<=2,'source archive nesting exceeds two levels');temporary.mkdir(parents=True,exist_ok=True)
    if path.is_file() and path.name.lower().endswith('.zip'):
        with zipfile.ZipFile(path) as archive:
            total=0;require(len(archive.infolist())<=10000,'too many archive entries')
            for item in archive.infolist():
                target=(temporary/item.filename).resolve();require(target.is_relative_to(temporary.resolve()) and not item.filename.startswith(('/','\\')) and not (item.external_attr>>16)&0o170000==0o120000,'unsafe model archive path');total+=item.file_size;require(total<=2*1024**3 and item.file_size<=1024**3,'model archive too large')
            archive.extractall(temporary)
        models=list(temporary.rglob('*.mlmodel'))
        if not models:
            packages=list(temporary.rglob('*.mlpackage.zip'));require(len(packages)==1,'archive must contain one readable .mlmodel or .mlpackage.zip');return model_file(packages[0],temporary/'nested',depth+1)
        require(len(models)==1,'archive must contain exactly one readable .mlmodel');return models[0]
    if path.is_dir():
        models=list(path.rglob('*.mlmodel'));require(len(models)==1,'package must contain exactly one readable .mlmodel');return models[0]
    require(path.suffix.lower()=='.mlmodel','input must be .mlmodel, .mlpackage directory or .mlpackage.zip');return path

def feature_shape(feature,override=None):
    kind=feature.type.WhichOneof('Type')
    if kind=='multiArrayType':shape=override or list(feature.type.multiArrayType.shape)
    elif kind=='imageType':
        image=feature.type.imageType;require(image.colorSpace in (10,20,30,40),'unsupported image colorspace');shape=[1,3 if image.colorSpace in (20,30) else 1,image.height,image.width]
        if override:require(len(override)==4 and override[0]==1,'image shape override must be NCHW batch 1');shape[-2:]=override[-2:]
    else:raise Unsupported(f'unsupported feature type {kind}')
    require(shape and all(x>0 for x in shape),'input has flexible/unknown shape; provide --shape 1,3,H,W');return shape

def convert(source,output,name,shape=None,allow_general=False,report_only=False,scale_override=None,shrink_override=None,tile_overlap=0,input_half_step=False,preserve_grayscale=True,metadata=None):
    output.mkdir(parents=True,exist_ok=True);safe=''.join(x for x in name if x.isalnum() or x in '-_.').strip('.');require(bool(safe),'model name has no safe filename characters')
    metadata=metadata or {};config={} if str(metadata.get('type','')).lower()=='image' else metadata.get('config') or {};report={**metadata,'name':name,'source':str(source),'sourceSha256':sha(source) if source.is_file() else None,'precision':'FP32 computation; original FP16/quantized weight values are decoded','unsupported':[]}
    with tempfile.TemporaryDirectory(prefix='aidoku-coreml-') as tmp:
        model_path=model_file(source,Path(tmp));spec=Model_pb2.Model();spec.ParseFromString(model_path.read_bytes());graph=Graph();kind=spec.WhichOneof('Type');report['coremlType']=kind
        require(bool(spec.description.input) and bool(spec.description.output),'model has no input/output');feature=next((f for f in spec.description.input if f.name==config.get('inputName')),spec.description.input[0]);output_feature=next((f for f in spec.description.output if f.name==config.get('outputName')),spec.description.output[0]);require(not config.get('inputName') or feature.name==config['inputName'],'configured inputName does not exist');require(not config.get('outputName') or output_feature.name==config['outputName'],'configured outputName does not exist');output_index=list(spec.description.output).index(output_feature)
        for other in spec.description.input:
            if other.name==feature.name:continue
            require(other.type.isOptional and other.type.WhichOneof('Type')=='multiArrayType','additional required/model-state inputs are not supplied by original Aidoku upscalers');array=other.type.multiArrayType;default=array.WhichOneof('defaultOptionalValue');require(default is not None,'optional array input has no numeric default');dtype={65568:np.float32,65552:np.float32,65600:np.float32,131104:np.int32}.get(array.dataType);require(dtype is not None,'unsupported optional input dtype');graph.const(np.full(feature_shape(other),getattr(array,default),dtype=dtype),other.name)
        source_input_shape=feature_shape(feature,shape);input_shape=list(source_input_shape);graph.shapes[feature.name]=source_input_shape;input_name=feature.name
        feature_kind=feature.type.WhichOneof('Type')
        image_mode=feature_kind=='imageType' and output_feature.type.WhichOneof('Type')=='imageType'
        if image_mode:input_half_step=False;preserve_grayscale=False
        require(feature_kind=='imageType' or feature.type.multiArrayType.dataType in (65552,65568,65600),'only floating tensor/image inputs are accepted')
        if feature_kind=='multiArrayType':
            count=math.prod(source_input_shape);pixels=count//3;block=math.isqrt(pixels)
            if count%3==0 and block*block==pixels:
                input_shape=[1,3,block,block]
                if source_input_shape!=input_shape:input_name='aidoku_rgb_tiles';graph.shapes[input_name]=input_shape;graph.node('Reshape',[input_name,graph.const(source_input_shape,dtype=np.int64)],[feature.name])
        preprocessing=getattr(spec,kind).preprocessing if kind in ('neuralNetwork','neuralNetworkClassifier','neuralNetworkRegressor') else []
        scalers=[p for p in preprocessing if p.featureName in ('',feature.name)]
        if feature_kind=='imageType' or scalers:
            if feature_kind=='imageType':input_shape[1]=3
            input_name='aidoku_normalized_rgb';graph.shapes[input_name]=input_shape;current=input_name
            if feature_kind=='imageType':
                if feature.type.imageType.colorSpace in (10,40):current=graph.node('ReduceSum',[graph.binary('Mul',current,graph.const(np.asarray([0.2126,0.7152,0.0722],dtype=np.float32).reshape(1,3,1,1))),graph.const([1],dtype=np.int64)],keepdims=1)
                if feature.type.imageType.colorSpace!=40:current=graph.binary('Mul',current,graph.const(255,dtype=np.float32))
                if feature.type.imageType.colorSpace==30:current=graph.node('Gather',[current,graph.const([2,1,0],dtype=np.int64)],axis=1)
            if kind in ('neuralNetwork','neuralNetworkClassifier','neuralNetworkRegressor'):current=nn_preprocess(graph,spec,feature,current)
            graph.node('Identity',[current],[feature.name])
        if kind in ('neuralNetwork','neuralNetworkClassifier','neuralNetworkRegressor'):outputs=lower_nn(graph,spec)
        elif kind=='mlProgram':outputs=lower_mil(graph,spec,model_path)
        elif kind in ('pipeline','pipelineClassifier','pipelineRegressor'):outputs=lower_pipeline(graph,spec,model_path)
        elif kind=='identity':
            destination=spec.description.output[0].name
            if feature.name!=destination:graph.node('Identity',[feature.name],[destination])
            outputs=[destination]
        else:raise Unsupported(f'Core ML model type {kind} needs an explicit converter')
        report.update(inputName=input_name,inputShape=input_shape,sourceInputShape=source_input_shape,processingMode='image' if image_mode else 'multiarray',graph=graph.inventory,unsupported=graph.errors)
        if feature_kind=='imageType' and feature.type.imageType.colorSpace in (10,40):report['imageColorConversion']='RGB to Rec.709 grayscale (0.2126,0.7152,0.0722); Apple Vision/color-management interpolation is not asserted pixel-identical'
        report_path=output/(safe+'.conversion.json')
        if graph.errors or report_only:
            report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            if graph.errors:raise Unsupported(f'{len(graph.errors)} unsupported/invalid nodes; see {report_path}')
            return report
        require(output_index<len(outputs),'selected output is missing from graph results');output_name=outputs[output_index]
        if output_feature.type.WhichOneof('Type')=='multiArrayType':
            raw_output_shape=graph.shapes.get(output_name)
            if raw_output_shape and all(x is not None and x>0 for x in raw_output_shape):
                count=math.prod(raw_output_shape);pixels=count//3;side=math.isqrt(pixels)
                if count%3==0 and side*side==pixels and raw_output_shape!=[1,3,side,side]:output_name=graph.node('Reshape',[output_name,graph.const([1,3,side,side],dtype=np.int64)])
        if output_feature.type.WhichOneof('Type')=='imageType':
            converted=output_name if output_feature.type.imageType.colorSpace==40 else graph.binary('Div',output_name,graph.const(255,dtype=np.float32))
            if output_feature.type.imageType.colorSpace==30:converted=graph.node('Gather',[converted,graph.const([2,1,0],dtype=np.int64)],axis=1)
            elif output_feature.type.imageType.colorSpace in (10,40):converted=graph.node('Concat',[converted]*3,axis=1)
            output_name=graph.node('Identity',[converted],['aidoku_normalized_output'])
        if output_name in graph.constants:graph.initializer(output_name)
        known_output=graph.shapes.get(output_name);output_info=H.make_tensor_value_info(output_name,T.FLOAT,known_output if known_output is not None else [None]*4)
        model=H.make_model(H.make_graph(graph.nodes,safe,[H.make_tensor_value_info(input_name,T.FLOAT,input_shape)],[output_info],list(graph.initializers.values())),opset_imports=[H.make_opsetid('',17)],producer_name='Aidoku portable Core ML graph converter');model.ir_version=8
        for prop,value in {'coreml.source_sha256':sha(model_path),'coreml.precision':report['precision']}.items():entry=model.metadata_props.add();entry.key=prop;entry.value=value
        model=onnx.shape_inference.infer_shapes(model);onnx.checker.check_model(model)
        dest=output/(safe+'.onnx')
        staged=tempfile.NamedTemporaryFile(prefix='.'+safe+'-',suffix='.onnx',dir=output,delete=False);staged.close();staged_path=Path(staged.name)
        try:
            onnx.save(model,staged_path)
            session=ort.InferenceSession(str(staged_path),providers=['CPUExecutionProvider']);data=np.random.default_rng(2026).random(input_shape,dtype=np.float32);result=session.run(None,{input_name:data})[0]
            require(np.isfinite(result).all(),'model produced NaN/Inf during CPU validation');output_shape=list(result.shape)
            compatible=len(input_shape)==4 and input_shape[:2]==[1,3] and all(x>0 for x in input_shape) and len(output_shape)==4 and output_shape[:2]==[1,3] and all(x>0 for x in output_shape) and (image_mode or input_shape[2]==input_shape[3] and output_shape[2]==output_shape[3])
            block=input_shape[-1] if input_shape else 1;size=output_shape[-1] if output_shape else 1;shrink=0;scale=0
            if compatible and not image_mode:
                require(scale_override is None or scale_override>0,'scale must be positive');factors=[scale_override] if scale_override is not None else sorted({factor for divisor in range(1,math.isqrt(size)+1) if size%divisor==0 for factor in (divisor,size//divisor)})
                candidates=[(factor,(block-size//factor)//2) for factor in factors if size%factor==0 and block>=size//factor and (block-size//factor)%2==0 and (shrink_override is None or (block-size//factor)//2==shrink_override)]
                require(bool(candidates),'output has no positive integer scale and symmetric input halo');scale,shrink=candidates[0]
                require(shrink<block/2,'input halo leaves no visible tile pixels')
            elif compatible:scale=1
            require(compatible or allow_general,'model converted but shape is not compatible with Android RGB upscaling; use --allow-general to retain it')
            require(0<=tile_overlap<max(1,block-2*shrink),'tile overlap exceeds visible tile size')
            sizes={'inputWidth':input_shape[-1] if input_shape else 1,'inputHeight':input_shape[-2] if len(input_shape)>1 else 1,'outputWidth':output_shape[-1] if output_shape else 1,'outputHeight':output_shape[-2] if len(output_shape)>1 else 1}
            for key,value in {'aidoku.scale':scale,'aidoku.shrinkSize':shrink,'aidoku.tileOverlap':tile_overlap,'aidoku.inputHalfStep':str(input_half_step).lower(),'aidoku.preserveGrayscale':str(preserve_grayscale).lower(),'aidoku.processingMode':'image' if image_mode else 'multiarray',**{'aidoku.'+key:value for key,value in sizes.items()}}.items():prop=model.metadata_props.add();prop.key=key;prop.value=str(value)
            for dimension,size in zip(model.graph.output[0].type.tensor_type.shape.dim,output_shape):dimension.ClearField('dim_param');dimension.dim_value=size
            onnx.save(model,staged_path)
            report.update(file=dest.name,sha256=sha(staged_path),inputName=input_name,outputName=output_name,inputShape=input_shape,outputShape=output_shape,**sizes,appCompatible=compatible,blockSize=block,scale=scale,shrinkSize=shrink,tileOverlap=tile_overlap,inputHalfStep=input_half_step,preserveGrayscale=preserve_grayscale,validation={'finite':True,'min':float(result.min()),'max':float(result.max())},operators=len(graph.nodes))
            # A failed conversion must not remove the user's previous working model.
            # Publish only after the graph, CPU execution and Android metadata agree.
            staged_path.replace(dest)
            (output/(safe+'.json')).write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            with zipfile.ZipFile(output/(safe+'.aidoku-model.zip'),'w',zipfile.ZIP_DEFLATED) as archive:archive.write(dest,dest.name);archive.writestr('model.json',json.dumps(report,ensure_ascii=False,indent=2))
            return report
        except Exception as error:
            report['unsupported'].append({'operator':'model CPU/shape validation','reason':str(error),'inputShapes':{input_name:input_shape},'outputShapes':{output_name:graph.shapes.get(output_name)}})
            report_path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
            raise
        finally:staged_path.unlink(missing_ok=True)

def main():
    if hasattr(sys.stdout,'reconfigure'):sys.stdout.reconfigure(encoding='utf-8')
    if hasattr(sys.stderr,'reconfigure'):sys.stderr.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('input',type=Path);parser.add_argument('--output',type=Path,default=Path.cwd()/'converted-models');parser.add_argument('--name');parser.add_argument('--metadata',type=Path,help='original ModelInfo sidecar; source bundles/adjacent sidecars are read automatically');parser.add_argument('--shape',help='fixed original tensor shape; adapters preserve planar RGB buffers');parser.add_argument('--block-size',type=int);parser.add_argument('--scale',type=int);parser.add_argument('--shrink-size',type=int);parser.add_argument('--tile-overlap',type=int);parser.add_argument('--input-half-step',action=argparse.BooleanOptionalAction,default=None);parser.add_argument('--preserve-grayscale',action=argparse.BooleanOptionalAction,default=None);parser.add_argument('--allow-general',action='store_true');parser.add_argument('--report-only',action='store_true');args=parser.parse_args()
    model_name=args.name or args.input.name.removesuffix('.zip').removesuffix('.mlpackage').removesuffix('.mlmodel');safe=''.join(x for x in model_name if x.isalnum() or x in '-_.').strip('.') or 'model';report_path=args.output/(safe+'.conversion.json');previous_report=report_path.stat().st_mtime_ns if report_path.exists() else None
    try:
        from model_metadata import read_metadata,convert_with_metadata
        metadata=read_metadata(args.input,args.metadata)
        if not args.name and metadata.get('name'):model_name=metadata['name'];safe=''.join(x for x in model_name if x.isalnum() or x in '-_.').strip('.') or 'model';report_path=args.output/(safe+'.conversion.json');previous_report=report_path.stat().st_mtime_ns if report_path.exists() else None
        shape=[int(x) for x in args.shape.split(',')] if args.shape else [1,3,args.block_size,args.block_size] if args.block_size else None
        result=convert_with_metadata(args.input.resolve(),args.output.resolve(),model_name,metadata,shape=shape,allow_general=args.allow_general,report_only=args.report_only,scale_override=args.scale,shrink_override=args.shrink_size,tile_overlap=args.tile_overlap,input_half_step=args.input_half_step,preserve_grayscale=args.preserve_grayscale);print(json.dumps({k:v for k,v in result.items() if k not in ('graph','unsupported')},ensure_ascii=False,indent=2))
    except Exception as error:
        args.output.mkdir(parents=True,exist_ok=True)
        if not report_path.exists() or report_path.stat().st_mtime_ns==previous_report:report_path.write_text(json.dumps({'source':str(args.input),'unsupported':[{'operator':'model configuration/load/validation','reason':str(error)}]},ensure_ascii=False,indent=2),encoding='utf-8')
        parser.exit(2,f'Conversion failed: {error}\nReport: {report_path}\n')
if __name__=='__main__':main()
