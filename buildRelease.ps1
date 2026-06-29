# buildRelease.ps1
# Builds all FairPlay branches and uploads JARs to Modrinth and/or CurseForge.
#
# Usage:
#   .\buildRelease.ps1 -Version 1.1.0                          # build only
#   .\buildRelease.ps1 -Version 1.1.0 -Upload                  # build + upload both
#   .\buildRelease.ps1 -Version 1.1.0 -Modrinth                # build + upload Modrinth only
#   .\buildRelease.ps1 -Version 1.1.0 -CurseForge              # build + upload CurseForge only
#   .\buildRelease.ps1 -Version 1.1.0 -SkipBuild -Modrinth     # upload Modrinth, skip build
#   .\buildRelease.ps1 -Version 1.1.0 -SkipBuild -CurseForge   # upload CurseForge, skip build
#   .\buildRelease.ps1 -Version 1.1.0 -SkipBuild -Upload       # upload both, skip build
#   Add -Changelog "text" to any upload command

param(
    [string]$Version    = "",
    [switch]$SkipBuild,
    [switch]$Upload,        # shorthand: -Modrinth + -CurseForge
    [switch]$Modrinth,
    [switch]$CurseForge,
    [string]$Changelog  = ""
)

# Expand -Upload shorthand
if ($Upload) { $Modrinth = $true; $CurseForge = $true }

# ── Branch definitions ────────────────────────────────────────────────────────
$branches = @(
    [pscustomobject]@{
        Branch           = "master"
        Suffix           = "mc26.2+"
        ModrinthVersions = @("26.2")
        CFVersionIds     = @(16500)          # 26.2 (type=1)
    },
    [pscustomobject]@{
        Branch           = "v1214"
        Suffix           = "mc1.21.4-26.1"
        ModrinthVersions = @("1.21.4","1.21.5","1.21.6","1.21.7","1.21.8","1.21.9","1.21.10","1.21.11","26.1","26.1.1","26.1.2")
        CFVersionIds     = @(12738,12988,13473,13574,13683,13933,13966,14417,16083,16084,16085)  # type=1
    },
    [pscustomobject]@{
        Branch           = "v1205"
        Suffix           = "mc1.20.5-1.21.3"
        ModrinthVersions = @("1.20.5","1.20.6","1.21","1.21.1","1.21.2","1.21.3")
        CFVersionIds     = @(11306,11307,11515,12735,12736,12737)  # type=1
    },
    [pscustomobject]@{
        Branch           = "v119"
        Suffix           = "mc1.19-1.20.4"
        ModrinthVersions = @("1.19","1.19.1","1.19.2","1.19.3","1.19.4","1.20","1.20.1","1.20.2","1.20.3","1.20.4")
        CFVersionIds     = @(9190,9261,9560,9561,9973,9974,9994,10326,10741,10742)  # type=1
    },
    [pscustomobject]@{
        Branch           = "v117"
        Suffix           = "mc1.17-1.18.2"
        ModrinthVersions = @("1.17","1.17.1","1.18","1.18.1","1.18.2")
        CFVersionIds     = @(8503,8897,8849,8897,9016)  # type=1
    }
)

# ── Platform config ───────────────────────────────────────────────────────────
$MODRINTH_PROJECT_ID = "uoS1aGhp"
$CF_PROJECT_ID       = "1507711"

# ── Build phase ───────────────────────────────────────────────────────────────
$builtJars = @()

if ($SkipBuild) {
    if (-not $Version) { Write-Host "ERROR: -Version required with -SkipBuild" -ForegroundColor Red; exit 1 }
    $releaseDir = "releases/$Version"
    if (-not (Test-Path $releaseDir)) { Write-Host "ERROR: $releaseDir not found - build first" -ForegroundColor Red; exit 1 }
    Write-Host "Skipping build, collecting JARs from $releaseDir" -ForegroundColor Cyan
    foreach ($b in $branches) {
        $jarPath = "$releaseDir/FairPlay-$Version-$($b.Suffix).jar"
        if (-not (Test-Path $jarPath)) { Write-Host "  WARNING: $jarPath not found, skipping" -ForegroundColor Yellow; continue }
        $builtJars += [pscustomobject]@{
            Path             = (Resolve-Path $jarPath).Path
            Name             = "FairPlay-$Version-$($b.Suffix).jar"
            Version          = $Version
            Suffix           = $b.Suffix
            ModrinthVersions = $b.ModrinthVersions
            CFVersionIds     = $b.CFVersionIds
        }
        Write-Host "  Found $jarPath" -ForegroundColor Green
    }
} else {
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

        $jar = Get-ChildItem "build/libs/FairPlay-*.jar" |
               Where-Object { $_.Name -notlike "*-sources*" } |
               Select-Object -First 1
        if (-not $jar) {
            Write-Host "  No JAR found" -ForegroundColor Yellow
            $failed += $b.Branch
            continue
        }

        $detectedVersion = $jar.BaseName -replace "^FairPlay-", ""
        $releaseVersion  = if ($Version) { $Version } else { $detectedVersion }
        $releaseDir      = "releases/$releaseVersion"
        New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

        $destName = "FairPlay-$releaseVersion-$($b.Suffix).jar"
        $destPath = "$releaseDir/$destName"
        Copy-Item $jar.FullName -Destination $destPath -Force
        Write-Host "  -> $destPath" -ForegroundColor Green

        $builtJars += [pscustomobject]@{
            Path             = (Resolve-Path $destPath).Path
            Name             = $destName
            Version          = $releaseVersion
            Suffix           = $b.Suffix
            ModrinthVersions = $b.ModrinthVersions
            CFVersionIds     = $b.CFVersionIds
        }
    }

    Write-Host "`n=== Restoring branch: $startBranch ===" -ForegroundColor Cyan
    git checkout $startBranch 2>&1 | Out-Null

    if ($failed.Count -gt 0) {
        Write-Host "`nFailed branches: $($failed -join ', ')" -ForegroundColor Red
    }
}

if (-not $Modrinth -and -not $CurseForge) {
    $rv = if ($Version) { $Version } else { "see above" }
    Write-Host "`nAll JARs in releases/$rv/ - use -Modrinth, -CurseForge or -Upload to publish." -ForegroundColor Green
    exit 0
}

Add-Type -AssemblyName System.Net.Http

# ── Modrinth upload ───────────────────────────────────────────────────────────
if ($Modrinth) {
    $tokenFile = ".modrinth-token"
    if (-not (Test-Path $tokenFile)) {
        Write-Host "`nMissing $tokenFile - skipping Modrinth." -ForegroundColor Yellow
    } else {
        $mrToken = (Get-Content $tokenFile -Raw).Trim()
        Write-Host "`n=== Uploading to Modrinth ===" -ForegroundColor Cyan

        foreach ($j in $builtJars) {
            Write-Host "  $($j.Name) ..." -NoNewline

            $data = @{
                name           = "FairPlay $($j.Version) ($($j.Suffix))"
                version_number = "$($j.Version)-$($j.Suffix)"
                changelog      = $Changelog
                dependencies   = @()
                game_versions  = $j.ModrinthVersions
                version_type   = "release"
                loaders        = @("paper")
                featured       = $false
                project_id     = $MODRINTH_PROJECT_ID
                file_parts     = @("jarfile")
                primary_file   = "jarfile"
            } | ConvertTo-Json -Compress

            try {
                $client = [System.Net.Http.HttpClient]::new()
                $client.DefaultRequestHeaders.Add("Authorization", $mrToken)
                $client.DefaultRequestHeaders.Add("User-Agent", "flyingfinger1/fairplay-build-script")

                $form = [System.Net.Http.MultipartFormDataContent]::new()
                $form.Add([System.Net.Http.StringContent]::new($data, [System.Text.Encoding]::UTF8, "application/json"), "data")

                $fc = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($j.Path))
                $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
                $form.Add($fc, "jarfile", $j.Name)

                $resp = $client.PostAsync("https://api.modrinth.com/v2/version", $form).Result
                $body = $resp.Content.ReadAsStringAsync().Result

                if ($resp.IsSuccessStatusCode) {
                    Write-Host " OK ($( ($body | ConvertFrom-Json).id ))" -ForegroundColor Green
                } else {
                    Write-Host " FAILED ($($resp.StatusCode))" -ForegroundColor Red
                    Write-Host "    $body" -ForegroundColor DarkRed
                }
            } finally { if ($client) { $client.Dispose() } }
        }
    }
}

# ── CurseForge upload ─────────────────────────────────────────────────────────
if ($CurseForge) {
    $tokenFile = ".curseforge-token"
    if (-not (Test-Path $tokenFile)) {
        Write-Host "`nMissing $tokenFile - skipping CurseForge." -ForegroundColor Yellow
    } else {
        $cfToken = (Get-Content $tokenFile -Raw).Trim()
        Write-Host "`n=== Uploading to CurseForge ===" -ForegroundColor Cyan

        foreach ($j in $builtJars) {
            Write-Host "  $($j.Name) ..." -NoNewline

            $metadata = @{
                changelog     = $Changelog
                changelogType = "markdown"
                displayName   = "FairPlay $($j.Version) ($($j.Suffix))"
                gameVersions  = $j.CFVersionIds
                releaseType   = "release"
            } | ConvertTo-Json -Compress

            try {
                $client = [System.Net.Http.HttpClient]::new()
                $client.DefaultRequestHeaders.Add("X-Api-Token", $cfToken)

                $form = [System.Net.Http.MultipartFormDataContent]::new()
                $form.Add([System.Net.Http.StringContent]::new($metadata, [System.Text.Encoding]::UTF8, "application/json"), "metadata")

                $fc = [System.Net.Http.ByteArrayContent]::new([System.IO.File]::ReadAllBytes($j.Path))
                $fc.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new("application/java-archive")
                $form.Add($fc, "file", $j.Name)

                $resp = $client.PostAsync("https://minecraft.curseforge.com/api/projects/$CF_PROJECT_ID/upload-file", $form).Result
                $body = $resp.Content.ReadAsStringAsync().Result

                if ($resp.IsSuccessStatusCode) {
                    Write-Host " OK ($( ($body | ConvertFrom-Json).id ))" -ForegroundColor Green
                } else {
                    Write-Host " FAILED ($($resp.StatusCode))" -ForegroundColor Red
                    Write-Host "    $body" -ForegroundColor DarkRed
                }
            } finally { if ($client) { $client.Dispose() } }
        }
    }
}

Write-Host "`nDone." -ForegroundColor Green
if ($Modrinth)    { Write-Host "  Modrinth:    https://modrinth.com/plugin/fairplay-challenge/versions" }
if ($CurseForge)  { Write-Host "  CurseForge:  https://www.curseforge.com/minecraft/bukkit-plugins/fairplay-challenge/files" }
