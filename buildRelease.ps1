# buildRelease.ps1
# Builds all FairPlay branches and collects the JARs in releases/<version>/.
# Run from the project root: .\buildRelease.ps1
# Optional: .\buildRelease.ps1 -Version 1.1.0  (override version in folder name)

param(
    [string]$Version = ""
)

$branches = @(
    [pscustomobject]@{ Branch = "master"; Suffix = "mc26.2+"          },
    [pscustomobject]@{ Branch = "v1214";  Suffix = "mc1.21.4-26.1"    },
    [pscustomobject]@{ Branch = "v1205";  Suffix = "mc1.20.5-1.21.3"  },
    [pscustomobject]@{ Branch = "v119";   Suffix = "mc1.19-1.20.4"    },
    [pscustomobject]@{ Branch = "v117";   Suffix = "mc1.17-1.18.2"    }
)

$startBranch = git rev-parse --abbrev-ref HEAD
$failed = @()

foreach ($b in $branches) {
    Write-Host "`n=== $($b.Branch) ===" -ForegroundColor Cyan
    git checkout $b.Branch 2>&1 | Out-Null

    gradle build 2>&1 | Select-String -Pattern "BUILD|error:|Error" | ForEach-Object { Write-Host $_ }

    if ($LASTEXITCODE -ne 0) {
        Write-Host "  BUILD FAILED" -ForegroundColor Red
        $failed += $b.Branch
        continue
    }

    $jar = Get-ChildItem "build/libs/FairPlay-*.jar" | Where-Object { $_.Name -notlike "*-sources*" } | Select-Object -First 1
    if (-not $jar) { Write-Host "  No JAR found" -ForegroundColor Yellow; $failed += $b.Branch; continue }

    # Derive version from JAR name (e.g. FairPlay-1.0.3.jar → 1.0.3)
    $detectedVersion = $jar.BaseName -replace "^FairPlay-", ""

    # Use explicit -Version param if given, otherwise use the detected one
    $releaseVersion = if ($Version) { $Version } else { $detectedVersion }
    $releaseDir = "releases/$releaseVersion"
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    $destName = "FairPlay-$releaseVersion-$($b.Suffix).jar"
    Copy-Item $jar.FullName -Destination "$releaseDir/$destName" -Force
    Write-Host "  -> $releaseDir/$destName" -ForegroundColor Green
}

Write-Host "`n=== Restoring branch: $startBranch ===" -ForegroundColor Cyan
git checkout $startBranch 2>&1 | Out-Null

if ($failed.Count -gt 0) {
    Write-Host "`nFailed branches: $($failed -join ', ')" -ForegroundColor Red
} else {
    $releaseVersion = if ($Version) { $Version } else { "see above" }
    Write-Host "`nAll JARs in releases/$releaseVersion/" -ForegroundColor Green
    Get-ChildItem "releases/$releaseVersion" | ForEach-Object { Write-Host "  $($_.Name)" }
}
