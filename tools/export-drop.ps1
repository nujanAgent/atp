# SPDX-License-Identifier: Apache-2.0
<#
.SYNOPSIS
    Writes the ATP drop for a tagged commit into a consumer's third_party/atp folder.

.DESCRIPTION
    Copies the AIDL, the wire module's and the provider kit's sources and tests, the provider's consumer ProGuard
    rules, the fixtures, SPEC.md, LICENSE and NOTICE exactly as the tagged commit stores them (raw blobs, LF), then writes
    README.md (provenance), .gitattributes (no line-ending conversion) and SHA256SUMS (one line per file, sorted).
    The same tag gives byte-identical drops in every consumer. wire/build.gradle.kts and provider/build.gradle.kts
    belong to the consumer and are left as they are.

.EXAMPLE
    pwsh tools/export-drop.ps1 -Tag atp-v0.22 -Dest ../consumer/third_party/atp
#>
param(
    [Parameter(Mandatory = $true)][string]$Tag,
    [Parameter(Mandatory = $true)][string]$Dest
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

# The folder's old files are deleted before the drop is written, so only a third_party/atp folder is accepted.
$Dest = [System.IO.Path]::GetFullPath($Dest)
if ((Split-Path -Leaf $Dest) -ne "atp" -or (Split-Path -Leaf (Split-Path -Parent $Dest)) -ne "third_party") {
    throw "Dest must be a folder named third_party/atp, not $Dest"
}

function Invoke-Git([string[]]$GitArgs) {
    $out = & git -C $repo @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git $($GitArgs -join ' ') failed" }
    return $out
}

function Get-BlobBytes([string]$Commit, [string]$Path) {
    $psi = [System.Diagnostics.ProcessStartInfo]::new("git")
    foreach ($a in @("-C", $repo, "cat-file", "blob", "${Commit}:$Path")) { $psi.ArgumentList.Add($a) }
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $proc = [System.Diagnostics.Process]::Start($psi)
    $buffer = [System.IO.MemoryStream]::new()
    $proc.StandardOutput.BaseStream.CopyTo($buffer)
    $proc.WaitForExit()
    if ($proc.ExitCode -ne 0) { throw "git cat-file failed for $Path" }
    return $buffer.ToArray()
}

$commit = (Invoke-Git @("rev-parse", "$Tag^{commit}")).Trim()
$sources = [ordered]@{
    "atp/aidl/" = "aidl/"; "atp/wire/src/" = "wire/src/"; "atp/provider/src/" = "provider/src/"; "atp/fixtures/" = "fixtures/"
}
$singles = [ordered]@{
    "atp/provider/consumer-rules.pro" = "provider/consumer-rules.pro"; "spec/SPEC.md" = "SPEC.md"; "LICENSE" = "LICENSE"; "NOTICE" = "NOTICE"
}
$listed = Invoke-Git (@("ls-tree", "-r", "--name-only", $commit, "--") + @($sources.Keys | ForEach-Object { $_.TrimEnd("/") }) + @($singles.Keys))

$consumerOwned = @("wire/build.gradle.kts", "provider/build.gradle.kts")
New-Item -ItemType Directory -Force -Path $Dest | Out-Null
$keep = @($consumerOwned | ForEach-Object { (Resolve-Path -LiteralPath (Join-Path $Dest $_) -ErrorAction SilentlyContinue)?.Path } | Where-Object { $_ })
Get-ChildItem -LiteralPath $Dest -Recurse -File -Force | Where-Object { $keep -notcontains $_.FullName } |
    Remove-Item -Force
Get-ChildItem -LiteralPath $Dest -Recurse -Directory -Force | Sort-Object { $_.FullName.Length } -Descending |
    Where-Object { -not (Get-ChildItem -LiteralPath $_.FullName -Force) } | Remove-Item -Force

$written = [System.Collections.Generic.List[string]]::new()
foreach ($path in $listed) {
    $target = $null
    foreach ($prefix in $sources.Keys) { if ($path.StartsWith($prefix)) { $target = $sources[$prefix] + $path.Substring($prefix.Length) } }
    if (-not $target -and $singles.Contains($path)) { $target = $singles[$path] }
    if (-not $target) { continue }
    $file = Join-Path $Dest $target
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $file) | Out-Null
    [System.IO.File]::WriteAllBytes($file, (Get-BlobBytes $commit $path))
    $written.Add($target)
}

$atpKt = [System.Text.Encoding]::UTF8.GetString((Get-BlobBytes $commit "atp/wire/src/main/kotlin/com/nujan_agent/atp/wire/Atp.kt"))
$spec = [regex]::Match($atpKt, 'SPEC_VERSION = "([^"]+)"').Groups[1].Value
$lf = "`n"
$readme = @(
    "# App Tools Protocol (ATP), vendored",
    "",
    "Do not edit these files. They are the ATP drop ``$Tag`` (commit ``$commit`` of https://github.com/nujanAgent/atp),",
    "spec version $spec, under the Apache License 2.0 (``LICENSE``, attribution in ``NOTICE``). To update them, run",
    "``tools/export-drop.ps1 -Tag <tag> -Dest <this folder>`` in the ATP repository. ``SHA256SUMS`` lists every file, so a test can detect an edit.",
    "",
    "``wire/build.gradle.kts`` and ``provider/build.gradle.kts`` belong to this repository and are not part of the drop.",
    ""
) -join $lf
[System.IO.File]::WriteAllText((Join-Path $Dest "README.md"), $readme, [System.Text.UTF8Encoding]::new($false))
$written.Add("README.md")
[System.IO.File]::WriteAllText((Join-Path $Dest ".gitattributes"), "* -text$lf", [System.Text.UTF8Encoding]::new($false))
$written.Add(".gitattributes")

$sha = [System.Security.Cryptography.SHA256]::Create()
$paths = [System.Collections.Generic.List[string]]::new([string[]]$written)
$paths.Sort([System.StringComparer]::Ordinal)
$lines = foreach ($p in $paths) {
    $bytes = [System.IO.File]::ReadAllBytes((Join-Path $Dest $p))
    $hex = -join ($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString("x2") })
    "$hex  $p"
}
[System.IO.File]::WriteAllText((Join-Path $Dest "SHA256SUMS"), (($lines -join $lf) + $lf), [System.Text.UTF8Encoding]::new($false))
Write-Output "Wrote $($written.Count) files and SHA256SUMS for $Tag ($commit) into $Dest"
