# /// script
# requires-python = ">=3.14"
# dependencies = ["onnx==1.23.2", "onnxruntime==1.29.0", "onnxruntime-qnn==2.8.0"]
# ///
"""Compile the SAM 3.1 click image encoder for SM8850 on x86-64 Linux."""

import argparse
from pathlib import Path
import resource

import onnx
import onnxruntime as ort
import onnxruntime_qnn as qnn

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("model_dir", type=Path, help="Folder with the exported encoder, its weights and click decoder")
args = parser.parse_args()
root = args.model_dir.resolve()
source = root / "image-encoder.onnx"
model = onnx.load(source, load_external_data=False)
model = onnx.utils.Extractor(model).extract_model(["image"], ["high0", "high1", "embedding"])
output = root / "sm8850"
output.mkdir(exist_ok=True)

name = "QNNExecutionProvider"
ort.register_execution_provider_library(name, qnn.get_library_path())
device = next(device for device in ort.get_ep_devices() if device.ep_name == name)
options = ort.SessionOptions()
options.intra_op_num_threads = 4
options.add_session_config_entry("ep.context_enable", "1")
options.add_session_config_entry("ep.context_embed_mode", "0")
wrapper = output / "encoder-sm8850.onnx"
options.add_session_config_entry("ep.context_file_path", str(wrapper))
options.add_provider_for_devices([device], {
    "backend_path": qnn.get_qnn_htp_path(),
    "enable_htp_prepare_only": "1",
    "htp_arch": "81",
    "soc_model": "87",
    "htp_graph_finalization_optimization_mode": "1",
})

# The trimmed graph stays next to the source weights so relative references resolve.
trimmed = source.with_name("click-image-encoder.onnx")
try:
    onnx.save(model, trimmed)
    print("Compiling the 1008 × 1008 SAM 3.1 image encoder for SM8850", flush=True)
    session = ort.InferenceSession(trimmed, sess_options=options)
    del session
finally:
    trimmed.unlink(missing_ok=True)

nodes = onnx.load(wrapper, load_external_data=False).graph.node
if len(nodes) != 1 or nodes[0].op_type != "EPContext":
    raise RuntimeError("The output does not contain a single standalone NPU partition")
print(f"{wrapper} (peak compiler RSS {resource.getrusage(resource.RUSAGE_SELF).ru_maxrss / 1024**2:.2f} GiB)")
