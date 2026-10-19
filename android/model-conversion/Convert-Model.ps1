param(
    [string]$InputPath,
    [string]$ModelName,
    [string]$OutputDirectory,
    [string]$MetadataPath,
    [switch]$Catalog,
    [string]$CatalogModel,
    [switch]$CachedCatalog,
    [string]$CatalogUrl='http://upscale.aidoku.app/models.json',
    [int]$BlockSize=0,
    [int]$Scale=0,
    [int]$ShrinkSize=-1,
    [switch]$BrowseFolder,
    [switch]$AllowGeneral,
    [string]$PythonPath='D:\Android\ModelTools\Scripts\python.exe'
)
$ErrorActionPreference='Stop'
$OutputEncoding=[System.Text.UTF8Encoding]::new()
[Console]::OutputEncoding=$OutputEncoding
Add-Type -AssemblyName System.Windows.Forms
if (!(Test-Path -LiteralPath $PythonPath -PathType Leaf)) { throw 'Python environment not found. Pass -PythonPath pointing to Python with coremltools, numpy, onnx and onnxruntime installed.' }
if ($Catalog -or $CatalogModel) {
    if (!$CatalogModel) {
        $catalogScript=Join-Path $PSScriptRoot 'model_catalog.py'
        $listArguments=@($catalogScript,'--catalog',$CatalogUrl,'--list')
        if ($CachedCatalog) {$listArguments+='--offline'}
        $catalogJson=(& $PythonPath @listArguments) -join "`n"
        if ($LASTEXITCODE -ne 0) { throw 'Unable to fetch the original Aidoku model catalog.' }
        $catalogResult=$catalogJson | ConvertFrom-Json
        $modelEntries=$catalogResult.models
        $window=[System.Windows.Forms.Form]::new();$window.Text='Aidoku official source models';$window.Width=640;$window.Height=360
        if ($catalogResult.cached) {$window.Text+=' (cached catalog)'}
        $choices=[System.Windows.Forms.ListBox]::new();$choices.Left=12;$choices.Top=12;$choices.Width=590;$choices.Height=245
        foreach($entry in $modelEntries) { [void]$choices.Items.Add("$($entry.name)  | $($entry.type) | v$($entry.version) | $($entry.file)") }
        $button=[System.Windows.Forms.Button]::new();$button.Text='Convert';$button.Left=505;$button.Top=265;$button.DialogResult=[System.Windows.Forms.DialogResult]::OK;$window.Controls.Add($choices);$window.Controls.Add($button);$window.AcceptButton=$button
        if ($choices.Items.Count -gt 0) {$choices.SelectedIndex=0}
        if ($window.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK -or $choices.SelectedIndex -lt 0) { return }
        $CatalogModel=$modelEntries[$choices.SelectedIndex].file
    }
} elseif (!$InputPath) {
    if ($BrowseFolder) {
        $picker=[System.Windows.Forms.FolderBrowserDialog]::new()
        $picker.Description='Choose a readable Core ML .mlpackage directory'
        if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
        $InputPath=$picker.SelectedPath
    } else {
        $picker=[System.Windows.Forms.OpenFileDialog]::new()
        $picker.Title='Choose a Core ML source model'
        $picker.Filter='Core ML source or exported bundle (*.mlmodel;*.zip)|*.mlmodel;*.zip|All files (*.*)|*.*'
        if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
        $InputPath=$picker.FileName
    }
}
if (!$OutputDirectory) {
    $picker=[System.Windows.Forms.FolderBrowserDialog]::new()
    $picker.Description='Choose a directory for the ONNX model and conversion report'
    if ($picker.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { return }
    $OutputDirectory=$picker.SelectedPath
}
$converterPath=Join-Path $PSScriptRoot 'convert_model.py'
if ($Catalog -or $CatalogModel) {$arguments=@((Join-Path $PSScriptRoot 'model_catalog.py'),'--catalog',$CatalogUrl,'--select',$CatalogModel,'--output',$OutputDirectory)}
else {$arguments=@($converterPath,(Resolve-Path -LiteralPath $InputPath).Path,'--output',$OutputDirectory)}
if ($MetadataPath -and !($Catalog -or $CatalogModel)) {$arguments+=@('--metadata',(Resolve-Path -LiteralPath $MetadataPath).Path)}
if ($ModelName) { $arguments+=@('--name',$ModelName) }
if ($BlockSize -gt 0) { $arguments+=@('--block-size',"$BlockSize") }
if ($Scale -gt 0) { $arguments+=@('--scale',"$Scale") }
if ($ShrinkSize -ge 0) { $arguments+=@('--shrink-size',"$ShrinkSize") }
if ($AllowGeneral) { $arguments+='--allow-general' }
if ($CachedCatalog -and ($Catalog -or $CatalogModel)) {$arguments+='--offline'}
& $PythonPath @arguments
if ($LASTEXITCODE -ne 0) { throw "Model conversion failed with code $LASTEXITCODE. Inspect the .conversion.json report in the output directory." }
Write-Host "Models and reports saved to $OutputDirectory"
