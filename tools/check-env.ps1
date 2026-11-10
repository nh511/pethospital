param(
  [string]$JavaHome = $env:JAVA_HOME,
  [string]$NodePath = 'node',
  [string]$PnpmPath = 'pnpm',
  [string]$DockerPath = 'docker',
  [string]$PythonPath = '',
  [string]$PythonImage = 'python:3.11.14-slim-bookworm@sha256:65a93d69fa75478d554f4ad27c85c1e69fa184956261b4301ebaf6dbb0a3543d'
)

$ErrorActionPreference = 'Continue'
$failures = 0

function Check([string]$Name, [scriptblock]$Probe) {
  try {
    $result = & $Probe
    if (-not $result) { throw 'Not available' }
    Write-Host "[OK] $Name : $result"
  }
  catch {
    $script:failures++
    Write-Host "[MISSING] $Name : $($_.Exception.Message)"
  }
}

function Invoke-PnpmVersion {
  if ($PnpmPath.EndsWith('.cjs', [StringComparison]::OrdinalIgnoreCase)) {
    return & $NodePath $PnpmPath --version
  }
  return & $PnpmPath --version
}

Check 'Java 21' {
  $java = if ($JavaHome) { Join-Path $JavaHome 'bin\java.exe' } else { 'java' }
  $version = (& $java -version 2>&1 | Out-String)
  if ($LASTEXITCODE -ne 0 -or $version -notmatch 'version "21[.]') { throw $version.Trim() }
  ($version -split "`r?`n")[0].Trim()
}

Check 'Maven Wrapper 3.9.9 on Java 21' {
  $wrapper = Join-Path $PSScriptRoot '..\server\mvnw.cmd'
  if (-not (Test-Path -LiteralPath $wrapper)) { throw 'server/mvnw.cmd not found' }
  $previousJavaHome = $env:JAVA_HOME
  try {
    if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
    $output = (& $wrapper -version 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0 -or $output -notmatch 'Apache Maven 3[.]9[.]9' -or $output -notmatch 'Java version: 21[.]') {
      throw $output.Trim()
    }
    'Apache Maven 3.9.9 / Java 21'
  }
  finally { $env:JAVA_HOME = $previousJavaHome }
}

Check 'Node 24' {
  $version = & $NodePath --version
  if ($LASTEXITCODE -ne 0 -or $version -notmatch '^v24[.]') { throw $version }
  $version
}

Check 'pnpm 10.20.0' {
  $version = Invoke-PnpmVersion
  if ($LASTEXITCODE -ne 0 -or $version -ne '10.20.0') { throw "Expected 10.20.0, got $version" }
  $version
}

Check 'Docker Linux Engine' {
  $version = & $DockerPath version --format '{{.Server.Version}}|{{.Server.Os}}' 2>$null
  if ($LASTEXITCODE -ne 0 -or $version -notmatch '^[^|]+[|]linux$') {
    throw 'Start Docker Desktop Linux Engine or grant access to its named pipe'
  }
  $version
}

Check 'Docker Compose plugin (major >= 2)' {
  $version = & $DockerPath compose version --short 2>$null
  if ($LASTEXITCODE -ne 0 -or $version -notmatch '^(?<major>\d+)[.]' -or [int]$Matches.major -lt 2) {
    throw 'Docker Compose plugin major version 2 or newer is unavailable'
  }
  $version
}

Check 'Python 3.11 runtime' {
  if ($PythonPath) {
    $version = & $PythonPath --version 2>&1
    if ($LASTEXITCODE -ne 0 -or $version -notmatch '^Python 3[.]11[.]') {
      throw "Expected Python 3.11, got $version"
    }
    return $version
  }

  $localVersion = & py -3.11 --version 2>&1
  if ($LASTEXITCODE -eq 0 -and $localVersion -match '^Python 3[.]11[.]') { return $localVersion }

  & $DockerPath image inspect $PythonImage *> $null
  if ($LASTEXITCODE -ne 0) {
    throw "Local Python 3.11 unavailable and pinned image is not present: $PythonImage"
  }
  $containerVersion = & $DockerPath run --rm --network none $PythonImage python --version 2>&1
  if ($LASTEXITCODE -ne 0 -or $containerVersion -ne 'Python 3.11.14') {
    throw "Pinned Python container failed: $containerVersion"
  }
  "$containerVersion (pinned container)"
}

if ($failures) {
  Write-Host "$failures prerequisite(s) unavailable"
  exit 1
}

Write-Host 'All locked runtime prerequisites are available'
exit 0
