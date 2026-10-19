"""Revalidate both official models without rewriting the stable bundled graphs."""
from pathlib import Path
import runpy
import onnxruntime as ort
from convert_model import convert
ort.set_default_logger_severity(3)
root=Path(__file__).resolve().parent
dest=root.parent/'verification/model-converter'
convert(root/'waifu2x_photo_noise0_scale2x.mlmodel',dest,'audited-waifu',shape=[1,3,156,156],scale_override=2,shrink_override=7,input_half_step=True)
convert(root/'RealESRGAN_x2plus.mlpackage',dest,'audited-esrgan',shape=[1,3,256,256],scale_override=2,tile_overlap=16,input_half_step=True)
runpy.run_path(str(root/'compare_official_graphs.py'),run_name='__main__')
