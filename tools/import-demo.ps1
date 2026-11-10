param([Parameter(Mandatory=$true)][string]$JavaHome)
$ErrorActionPreference='Stop'

$required=@('DB_URL','MYSQL_USER','MYSQL_PASSWORD','DEMO_PASSWORD','DEMO_FIXTURE_CONFIRM')
foreach($name in $required){
 if([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name))){
  throw "$name must be set in the current process environment"
 }
}

$projectRoot=Split-Path $PSScriptRoot -Parent
$mavenRunner=Join-Path $PSScriptRoot 'run-maven.ps1'
Push-Location $projectRoot
try {
 & $mavenRunner -JavaHome $JavaHome -MavenArgs @(
  '-o',
  '-Dspring-boot.run.main-class=com.pethospital.persistence.demo.DemoFixtureCommand',
  'spring-boot:run'
 )
} finally {
 Pop-Location
}
