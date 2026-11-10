param(
 [Parameter(Mandatory=$true)][string]$JavaHome,
 [switch]$LoadDotEnv,
 [Parameter(ValueFromRemainingArguments=$true)][string[]]$MavenArgs=@('test')
)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path $PSScriptRoot -Parent
$java=Join-Path $JavaHome 'bin\java.exe'
$description=(& $java -version 2>&1 | Out-String)
if($description -notmatch 'version "21[.]'){throw 'A Java21 runtime is required'}
$previousJava=$env:JAVA_HOME
$previousPath=$env:PATH
$previousDotEnv=@{}
try {
 if($LoadDotEnv){
  $envPath=Join-Path $projectRoot '.env'
  if(-not (Test-Path -LiteralPath $envPath)){throw '.env is required when -LoadDotEnv is used'}
  foreach($line in [System.IO.File]::ReadAllLines($envPath)){
   if([string]::IsNullOrWhiteSpace($line) -or $line.TrimStart().StartsWith('#') -or -not $line.Contains('=')){continue}
   $key,$value=$line.Split('=',2)
   $key=$key.Trim()
   if($key -notmatch '^[A-Za-z_][A-Za-z0-9_]*$'){throw 'Invalid environment key in .env'}
   $previousDotEnv[$key]=[Environment]::GetEnvironmentVariable($key,'Process')
   [Environment]::SetEnvironmentVariable($key,$value,'Process')
  }
 }
 $env:JAVA_HOME=$JavaHome
 $env:PATH="$JavaHome\bin;$previousPath"
 $pom=Join-Path $projectRoot 'server\pom.xml'
 $repo=Join-Path $projectRoot '.tools\m2'
 & mvn -B -f $pom "-Dmaven.repo.local=$repo" @MavenArgs
 if($LASTEXITCODE -ne 0){throw "Maven failed: $LASTEXITCODE"}
} finally {
 foreach($entry in $previousDotEnv.GetEnumerator()){
  [Environment]::SetEnvironmentVariable($entry.Key,$entry.Value,'Process')
 }
 $env:JAVA_HOME=$previousJava
 $env:PATH=$previousPath
}
