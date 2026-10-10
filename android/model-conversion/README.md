# Aidoku Core ML model conversion

The Android reader runs real ONNX graphs with the original model weights. Both models in Aidoku's original index are bundled: waifu2x and Real-ESRGAN. `convert_coreml.py` reproduces those two stable bundled assets; `convert_model.py` and `model_metadata.py` convert readable custom or future catalogue NN/MIL models without requiring an Apple compiler.

In this repository, runtime and original model data are attached to the `v0.9` release instead of stored in Git. From the repository root, run `python android/scripts/prepare-assets.py --with-original-models` to download and checksum both sets. The converter's original weights and readable model graph are restored under this directory; no source model has been replaced with a placeholder.

## Windows and command-line entry points

The prepared Python environment is `D:\Android\ModelTools\Scripts\python.exe` with coremltools 9, NumPy, ONNX and ONNX Runtime. The Android app does not need Python.

```powershell
# Official directory: select by original file, display name, or numeric index.
powershell -NoProfile -ExecutionPolicy Bypass -File android\model-conversion\Convert-Model.ps1 -Catalog -CatalogModel models/waifu2x_photo_noise0_scale2x.mlmodel -OutputDirectory C:\Models\Aidoku

# A raw model bundle exported by the Android model catalogue screen.
powershell -NoProfile -ExecutionPolicy Bypass -File android\model-conversion\Convert-Model.ps1 -InputPath C:\Models\model.aidoku-coreml.zip -OutputDirectory C:\Models\Aidoku

# Standalone source with optional original ModelInfo sidecar.
D:\Android\ModelTools\Scripts\python.exe android\model-conversion\convert_model.py C:\Models\custom.mlpackage.zip --metadata C:\Models\originalmodel.json --output C:\Models\Aidoku --name "Custom model"

# Explicit tile overrides when the source has flexible dimensions.
D:\Android\ModelTools\Scripts\python.exe android\model-conversion\convert_model.py C:\Models\custom.mlmodel --output C:\Models\Aidoku --block-size 128 --scale 2 --shrink-size 7
```

Without input/output arguments, the PowerShell entry opens native file and destination-directory dialogs. `-BrowseFolder` selects a `.mlpackage` directory. `-Catalog` opens an official model selector; `-CachedCatalog` explicitly uses its last verified directory. A failed online refresh can also return the cached directory, clearly marked as cached. Its URL must match the requested catalogue. Retry without `-CachedCatalog` to refresh.

The Python catalogue entry supports `--list`, `--updates`, `--select`, `--all`, `--catalog`, `--catalog-cache` and `--offline`. Update comparisons use original `catalogFile` and `version`, so an ONNX filename can change without losing catalogue identity. Raw URLs resolve relative to the directory URL, and `.mlpackage` downloads append `.zip` while retaining query parameters. Size limits, optional catalogue SHA-256 checks and readable errors apply to actual downloads.

The original directory is `http://upscale.aidoku.app/models.json`. On this host, the original-style `Monogatari/0.9` User-Agent fetched both entries and downloaded/converted the actual waifu2x source. HTTPS and ordinary urllib requests returned HTTP 403; those failures do not establish that the original HTTP request is unavailable. The successful Windows command and resulting metadata are saved under `android/verification/model-converter/catalog-live` and `catalog-live.log`.

## Source exports and metadata

Accepted inputs are readable `.mlmodel`, `.mlpackage` directories, `.mlpackage.zip`, and Android `.aidoku-coreml.zip` source exports. An Android export contains complete original `model.json` plus `source.mlmodel` or nested `source.mlpackage.zip`. An explicit `--metadata` file takes priority; otherwise root bundle `model.json`, `<input>.json`, a same-stem JSON sidecar, or sibling `model.json` supplies original ModelInfo.

The converter retains `type`, `name`, `info`, `tags`, `version`, `config`, unknown fields, `catalogFile` and `sourceFile`. Original MultiArray `config.inputName` and `config.outputName` select graph features; numeric optional extra inputs use their declared defaults. Additional required/state inputs are rejected because the original upscalers do not supply them. Original tile options (`shape`, `blockSize`, `scale`, `shrinkSize`, `preserveGrayscale`) remain authoritative unless overridden.

Successful conversion writes `<name>.onnx`, `<name>.json`, `<name>.conversion.json`, and `<name>.aidoku-model.zip` containing ONNX plus `model.json`. Import the ZIP or ONNX in the Android model page. ONNX metadata and the manifest record final input/output names and dimensions, `processingMode`, `scale`, `shrinkSize`, `tileOverlap`, `inputHalfStep` and `preserveGrayscale`. The manifest retains original catalogue identity and version for future comparisons.

Archive loading rejects traversal, symlinks, ambiguous models/metadata, excessive entries, oversized payloads and nesting beyond the source-export/package layers. Weights are read from public protobuf references and 64-byte `0xDEADBEEF` blob headers; absent/truncated graph or weight data fail explicitly.

## Original upscaler behavior

`ImageModel.swift` submits the entire image to Vision with `.scaleFill` and reads a pixel-buffer output. Its config is preserved as metadata but ignored for processing, matching the original initializer. Android `processingMode=image` performs one whole-image inference at declared input width/height, then uses the fixed model output width/height. Rectangular shapes, grayscale image features and output sizes beyond 4× are supported. Canonical RGB [0,1] is adapted to original RGB/BGR 8-bit channels, declared scale/bias/mean-image preprocessing, or normalized Float16 grayscale; image outputs return canonical RGB. Grayscale adaptation uses explicit Rec.709 coefficients. Public graph adapters are independently tested; Apple Vision's private resampling/color/pixel-buffer implementation is not asserted to have identical rounding.

`MultiArrayModel.swift` writes three planar RGB channels into the configured array and tiles a page. Rank-one, rank-three and legacy rank-five custom declarations are adapted by element-preserving reshapes to external NCHW RGB. Any positive integer configured scale and valid block/halo geometry are accepted, including 3-pixel blocks and 5× models. The original half 8-bit step applies to real pixels; replicated halo pixels receive no bias. Valid-convolution shrink, overlapping tile blending and grayscale preservation retain separate metadata. Output allocation is bounded by actual Android memory budgets rather than the former artificial 16–512 block/1–4 scale restriction.

The stable bundled hashes remain:

- waifu2x: `c283e1130e54e7220d7b0fe1a800a0155623a9dbf9d335be3f3b6dc831f44358`
- Real-ESRGAN: `98f4e7531f5fd0552ee98c1fd618985741225a04ca8ecc532610785dafbeb34c`

## Public graph coverage and boundaries

`audit_operator_coverage.py` compares dispatch code with the installed public NN protobuf and MIL operator registries. Its report is `android/verification/model-converter/operator-coverage.json`. Lowering routes exist for 157 NN and 165 MIL core operators, including nested pipelines, conditionals/loops, mutable NN values, tensor lists with runtime-resolved symbolic element shapes and fixed/growing lengths, RNN/GRU/LSTM, spatial/arithmetic operations, scatter reductions, dynamic slices/resizes/padding/tile repetitions, ROI sampling, NMS and deployed sparse/LUT/blockwise weight decompression. FP16, NN 1–8-bit linear/LUT weights, MIL low-bit blobs and vector palettes are decoded.

These counts are a dispatch inventory, **not proof that every parameter combination or graph has been numerically verified**. The report lists every explicit parameter guard with its line and condition. Selected upscaler input must resolve to a fixed shape; `--shape` or `--block-size` selects flexible declarations. Some ROI/resample/scatter/NMS/recurrent/NN operations require concrete intermediate dimensions. Dynamic slices, resize sizes, list lengths and loop values have runtime lowerings; unresolved ranks or unsupported parameters produce explicit diagnostics and are converter coverage boundaries, not Apple-platform impossibilities.

The excluded schema entries are precise: custom NN nodes require a caller-supplied kernel; symbolic conversion constants need deployment values; classifiers produce labels/dictionaries outside the original RGB pixel-output contract; state operators require MLState that original upscalers do not create. Apple's complex conversion dialect lowers to real-valued deployment operators before MLProgram serialization. Experimental `conv_quantized` linear bindings work; its LUT spelling exposes no palette binding in the public op. NN LUT and deployed MIL `constexpr` LUT models with supplied palettes are decoded.

Unsupported nodes are accumulated with names, operators, input/output shapes/types and reasons. CPU inference validates finite output and selected Android geometry before publishing. A failed conversion preserves the working model. `--report-only` audits without publishing; `--allow-general` retains validated tensors outside the original pixel contract with `appCompatible=false`.

FP16-stored values use FP32 inference. Apple intermediate FP16 rounding, dynamic INT8 activation quantization choices, seeded random sequences and Vision pixel conversion can differ between engines. Reports preserve these precision distinctions. Compiled-only `.mlmodelc` needs recoverable source graph/weights; an opaque kernel name or compiled bytes alone do not describe its computation.

## Reproducible verification

```powershell
D:\Android\ModelTools\Scripts\python.exe -m unittest discover -s android\model-conversion -p "test_*.py" -v
D:\Android\ModelTools\Scripts\python.exe android\model-conversion\audit_operator_coverage.py
D:\Android\ModelTools\Scripts\python.exe android\model-conversion\audit_official_models.py
```

**82 CPU tests pass** (61 converter and 21 catalogue/contract tests), comparing actual ONNX Runtime output with independently calculated NumPy results. Checks include control flow, recurrent gate equations/states, scatter modes, ROI/resize coordinates, quantization, custom shapes, image/BGR/gray preprocessing, pipelines, unset defaults, full-reduction rank-one semantics, uint casts, symbolic/fixed tensor-list behavior, image-dependent padding/tile values, selected outputs, source bundles, actual loopback HTTP downloads/version updates and visible URL-scoped catalogue caching. `latest-tests.log` records the current run.

The general converter ran both official models on CPU and compared **complete output tensors** to the stable bundled graphs on identical deterministic inputs. Both had `maxAbsDifference=0` and exact array equality: waifu2x [1,3,156,156] → [1,3,284,284] and Real-ESRGAN [1,3,256,256] → [1,3,512,512]. `official-graph-comparison.json` records this. It proves agreement between two independent portable graph reconstructions, not with an Apple runtime.

Device fixtures include FP16, quantized and MIL models plus rectangular whole-image, grayscale whole-image and 5× models. Files and CPU checks are retained in `android/verification/custom-models` and `android/verification/model-converter/image-fixtures`; device results are recorded by the coordinated Android suite. Host tests alone do not claim device tests passed.

Primary specifications: [Apple NN protobuf](https://github.com/apple/coremltools/blob/main/mlmodel/format/NeuralNetwork.proto), [Apple public MIL definitions](https://github.com/apple/coremltools/tree/main/coremltools/converters/mil/mil/ops/defs), [Apple NN quantization utilities](https://apple.github.io/coremltools/_modules/coremltools/models/neural_network/quantization_utils.html), [ONNX operators](https://onnx.ai/onnx/operators/).

