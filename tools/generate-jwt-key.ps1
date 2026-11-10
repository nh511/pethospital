param(
    [string]$OutputDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) '.local')
)

$ErrorActionPreference = 'Stop'
$privatePath = Join-Path $OutputDirectory 'jwt-private.pem'
$publicPath = Join-Path $OutputDirectory 'jwt-public.pem'

if ((Test-Path -LiteralPath $privatePath) -or (Test-Path -LiteralPath $publicPath)) {
    throw 'JWT key material already exists; refusing to overwrite it.'
}

New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$rsa = [System.Security.Cryptography.RSA]::Create(2048)
try {
    $privateBase64 = [Convert]::ToBase64String(
        $rsa.ExportPkcs8PrivateKey(),
        [Base64FormattingOptions]::InsertLineBreaks
    )
    $publicBase64 = [Convert]::ToBase64String(
        $rsa.ExportSubjectPublicKeyInfo(),
        [Base64FormattingOptions]::InsertLineBreaks
    )
    $privatePem = "-----BEGIN PRIVATE KEY-----`r`n$privateBase64`r`n-----END PRIVATE KEY-----`r`n"
    $publicPem = "-----BEGIN PUBLIC KEY-----`r`n$publicBase64`r`n-----END PUBLIC KEY-----`r`n"
    [IO.File]::WriteAllText($privatePath, $privatePem, [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText($publicPath, $publicPem, [Text.UTF8Encoding]::new($false))
} finally {
    $rsa.Dispose()
}

Write-Output "JWT key pair created in $OutputDirectory. Keep jwt-private.pem secret."
