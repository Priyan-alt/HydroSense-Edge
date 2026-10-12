Add-Type -AssemblyName System.Drawing

$srcPath = "C:\Users\Admin\.gemini\antigravity-ide\brain\107d7133-b3f7-4080-bab6-e7d8c8062f8f\.user_uploaded\media_1791480420268.jpg"
$resDir = "C:\Users\Admin\.gemini\antigravity-ide\scratch\HydroSenseEdge\app\src\main\res"

$srcImg = [System.Drawing.Image]::FromFile($srcPath)

# 1. High-res drawable for in-app UI
$drawableDir = Join-Path $resDir "drawable"
if (!(Test-Path $drawableDir)) { New-Item -ItemType Directory -Path $drawableDir -Force | Out-Null }
$drawableTarget = Join-Path $drawableDir "ic_hydrosense_logo.png"
$srcImg.Save($drawableTarget, [System.Drawing.Imaging.ImageFormat]::Png)
Write-Host "Created high-res logo: $drawableTarget"

# Function to save resized high quality image
function Resize-ImageFile($source, $targetPath, $size) {
    $parent = Split-Path $targetPath
    if (!(Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $g.DrawImage($source, 0, 0, $size, $size)
    $bmp.Save($targetPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
    Write-Host "Created: $targetPath ($size x $size)"
}

$densities = @(
    @{ Folder = "mipmap-mdpi"; Size = 48 },
    @{ Folder = "mipmap-hdpi"; Size = 72 },
    @{ Folder = "mipmap-xhdpi"; Size = 96 },
    @{ Folder = "mipmap-xxhdpi"; Size = 144 },
    @{ Folder = "mipmap-xxxhdpi"; Size = 192 }
)

foreach ($d in $densities) {
    $folder = Join-Path $resDir $d.Folder
    Resize-ImageFile $srcImg (Join-Path $folder "ic_launcher.png") $d.Size
    Resize-ImageFile $srcImg (Join-Path $folder "ic_launcher_round.png") $d.Size
}

# Adaptive icon foreground
$nodpiDir = Join-Path $resDir "drawable-nodpi"
Resize-ImageFile $srcImg (Join-Path $nodpiDir "ic_launcher_foreground.png") 432

$srcImg.Dispose()
Write-Host "All icons generated successfully!"
