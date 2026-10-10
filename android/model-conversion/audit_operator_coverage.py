"""Inventory public Core ML dispatch routes; this is not a numerical conformance proof."""
import ast,json
from pathlib import Path
from coremltools.proto import NeuralNetwork_pb2
from coremltools.converters.mil.mil.ops.registry import SSAOpRegistry
import coremltools.converters.mil.mil.ops

ROOT=Path(__file__).resolve().parent
def strings(node):
    if isinstance(node,ast.Constant) and isinstance(node.value,str):return {node.value}
    if isinstance(node,(ast.List,ast.Tuple,ast.Set)):return set().union(*(strings(x) for x in node.elts))
    return set()

def routes(tree,name):
    function=next(x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name==name);names=set();prefixes=set()
    for node in ast.walk(function):
        if isinstance(node,ast.Assign) and isinstance(node.value,ast.Dict) and any(isinstance(x,ast.Name) and x.id in ('unary','binary') for x in node.targets):
            names.update(set().union(*(strings(x) for x in node.value.keys if x is not None)))
        if isinstance(node,ast.Compare) and isinstance(node.left,ast.Name) and node.left.id=='kind':
            for operator,other in zip(node.ops,node.comparators):
                if isinstance(operator,(ast.Eq,ast.In)):names.update(strings(other))
        if isinstance(node,ast.Call) and isinstance(node.func,ast.Attribute) and isinstance(node.func.value,ast.Name) and node.func.value.id=='kind' and node.func.attr=='startswith':
            for argument in node.args:prefixes.update(strings(argument))
    return names,prefixes

def main():
    tree=ast.parse((ROOT/'convert_model.py').read_text(encoding='utf8'));nn,pnn=routes(tree,'lower_nn');mil,pmil=routes(tree,'lower_mil_block');mil.add('const')
    excluded={
        'custom':'The source names a caller-supplied custom kernel; its implementation is absent from the Core ML protobuf.',
        '_const_symbolic':'A deployment graph must replace symbolic conversion-time constants with concrete model values.',
        'classify':'Produces labels and probability dictionaries, outside the original RGB ImageModel/MultiArrayModel pixel output contract.',
        'read_state':'Requires MLState supplied to prediction(using:state:); the original Aidoku upscalers supply only a single image/array feature.',
        'coreml_update_state':'Requires an MLState object, which the original Aidoku upscalers do not create or pass.'}
    def inventory(names,implemented,prefixes):
        return [{'operator':name,'status':'outside_original_upscaler_contract' if name in excluded else 'lowering_present' if name in implemented or any(name.startswith(prefix) for prefix in prefixes) else 'implementation_gap',**({'reason':excluded[name]} if name in excluded else {})} for name in sorted(names)]
    nn_names=[field.name for field in NeuralNetwork_pb2.NeuralNetworkLayer.DESCRIPTOR.oneofs_by_name['layer'].fields]
    sections={'neuralNetwork':inventory(nn_names,nn,pnn),'milCore':inventory(SSAOpRegistry.core_ops,mil,pmil)}
    guards=[]
    for function in [node for node in tree.body if isinstance(node,ast.FunctionDef)]:
        for call in ast.walk(function):
            if isinstance(call,ast.Call) and isinstance(call.func,ast.Name) and call.func.id=='require' and len(call.args)>1:
                guards.append({'function':function.name,'line':call.lineno,'condition':ast.unparse(call.args[0]),'diagnostic':ast.unparse(call.args[1])})
    # Complex dialect operators are conversion intermediates. Apple's deployment
    # pass lowers them to real-valued core operators before writing MLProgram.
    sections['milDialect']=[{'operator':name,'status':'deployment_lowering' if 'complex' in name else 'outside_original_upscaler_contract','reason':'Core ML complex dialect lowering converts this to real-valued public operators before deployment.' if 'complex' in name else excluded.get(name,'Conversion-time dialect outside original pixel inference.')} for name in sorted(SSAOpRegistry.dialect_ops)]
    counts={section:{status:sum(row['status']==status for row in rows) for status in sorted(set(row['status'] for row in rows))} for section,rows in sections.items()}
    report={'schema':'coremltools '+__import__('coremltools').__version__,'method':'AST dispatch inventory compared with installed public NN protobuf and MIL registries. A route does not prove every parameter combination; explicitParameterGuards contains current require conditions, including fixed-rank/shape conversion boundaries. Independent CPU tests verify representative numeric semantics.','counts':counts,'operators':sections,'explicitParameterGuards':guards,'conversionBoundaries':['The selected original upscaler image/array feature must have a concrete fixed input shape; flexible declarations need a --shape/--block-size selection.','Crop-resize, resample, NMS, scatter, recurrent sequence dimensions, and several shape-dependent NN operators require concrete intermediate shapes. Runtime slice/resize/loop values are supported; unresolvable ranks are reported as converter coverage, not a platform impossibility.','Experimental conv_quantized linear bindings work; its LUT spelling exposes no palette binding in the public Core ML op. Provided NN LUT and deployed MIL constexpr LUT weights are decoded.','Pipelines may contain NN/MIL/identity/nested pipeline stages; other model families are not original pixel upscaler graph stages.'],'precisionDifferences':['FP16 values and intermediate arithmetic are promoted to FP32.','Public dynamic INT8 stored weights are dequantized; Apple activation quantization rounding is unspecified.','Vision image resize/color conversion and pixel-buffer rounding cannot be compared with Apple inference on this Windows host.','Random operators preserve distribution and seed metadata; independent engine random sequences may differ.']}
    path=ROOT.parent/'verification/model-converter/operator-coverage.json';path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8');print(json.dumps(counts,indent=2))
    missing=[f'{section}:{row["operator"]}' for section,rows in sections.items() for row in rows if row['status']=='implementation_gap']
    if missing:raise SystemExit('Missing public dispatch: '+', '.join(missing))
if __name__=='__main__':main()
