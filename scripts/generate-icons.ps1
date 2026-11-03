# Windows developer utility. The build uses committed PNGs, no drawing dependency required.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
$resourceDirectory = Join-Path (Split-Path $PSScriptRoot -Parent) 'app\src\main\res'
$bitmap = New-Object Drawing.Bitmap 432, 432
$graphics = [Drawing.Graphics]::FromImage($bitmap)
$graphics.SmoothingMode = [Drawing.Drawing2D.SmoothingMode]::AntiAlias
$graphics.Clear([Drawing.Color]::Transparent)
$graphics.ScaleTransform(4, 4)
$silhouette = New-Object Drawing.Drawing2D.GraphicsPath
$silhouette.AddArc(4, 4, 24, 24, 180, 90)
$silhouette.AddArc(80, 4, 24, 24, 270, 90)
$silhouette.AddArc(80, 80, 24, 24, 0, 90)
$silhouette.AddArc(4, 80, 24, 24, 90, 90)
$silhouette.CloseFigure()
$amber = New-Object Drawing.SolidBrush ([Drawing.ColorTranslator]::FromHtml('#A86300'))
$graphics.FillPath($amber, $silhouette)
$amber.Dispose()
$silhouette.Dispose()
$white = [Drawing.Brushes]::White
$pen = New-Object Drawing.Pen ([Drawing.Color]::White), 6
try {
    $graphics.DrawEllipse($pen, 66, 49, 17, 20)
    $graphics.FillRectangle($white, 32, 40, 40, 40)
    $graphics.FillRectangle($white, 38, 78, 28, 8)
    $graphics.FillEllipse($white, 32, 74, 12, 12)
    $graphics.FillEllipse($white, 60, 74, 12, 12)
    $graphics.FillEllipse($white, 33, 29, 18, 18)
    $graphics.FillEllipse($white, 44, 23, 20, 20)
    $graphics.FillEllipse($white, 58, 31, 16, 16)
    $densities = @{ mdpi = 48; hdpi = 72; xhdpi = 96; xxhdpi = 144; xxxhdpi = 192 }
    foreach ($density in $densities.Keys) {
        $directory = Join-Path $resourceDirectory "mipmap-$density"
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
        $size = $densities[$density]
        $output = New-Object Drawing.Bitmap $size, $size
        $scaledGraphics = [Drawing.Graphics]::FromImage($output)
        try {
            $scaledGraphics.InterpolationMode = [Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
            $scaledGraphics.DrawImage($bitmap, 0, 0, $size, $size)
            $output.Save((Join-Path $directory 'ic_launcher.png'), [Drawing.Imaging.ImageFormat]::Png)
        } finally { $scaledGraphics.Dispose(); $output.Dispose() }
    }
} finally { $pen.Dispose(); $graphics.Dispose(); $bitmap.Dispose() }
