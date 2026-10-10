"""Usage: python3 bench/quantize.py <model.onnx> <model.int8.onnx>  (dynamic int8 weights)"""
import sys
from onnxruntime.quantization import QuantType, quantize_dynamic
quantize_dynamic(sys.argv[1], sys.argv[2], weight_type=QuantType.QUInt8)
