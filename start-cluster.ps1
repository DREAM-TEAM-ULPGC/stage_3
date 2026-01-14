# Script para levantar el cluster en orden correcto
# Primero infraestructura, luego servicios, finalmente nginx

param(
    [switch]$Down
)

$composePath = "d:\Documentos\DOCUMENTOS PERSONALES\BIG DATA\stage3"

Set-Location $composePath

if ($Down) {
    Write-Host "Deteniendo cluster..." -ForegroundColor Yellow
    docker-compose -f docker-compose-full.yml down
    exit 0
}

Write-Host "=== Levantando Gutenberg Distributed Cluster ===" -ForegroundColor Cyan
Write-Host ""

# Paso 1: Infraestructura (ActiveMQ + Hazelcast)
Write-Host "1. Levantando infraestructura (ActiveMQ + Hazelcast)..." -ForegroundColor Yellow
docker-compose -f docker-compose-full.yml up -d activemq hazelcast-1 hazelcast-2 hazelcast-3

Write-Host "   Esperando 20 segundos para que la infraestructura inicie..." -ForegroundColor Gray
Start-Sleep -Seconds 20

# Paso 2: Servicios de aplicacion
Write-Host "2. Levantando servicios (Ingestion, Indexer, Search)..." -ForegroundColor Yellow
docker-compose -f docker-compose-full.yml up -d ingestion-1 ingestion-2 indexer-1 indexer-2 search-1 search-2 search-3

Write-Host "   Esperando 30 segundos para que los servicios inicien..." -ForegroundColor Gray
Start-Sleep -Seconds 30

# Paso 3: Nginx Load Balancer
Write-Host "3. Levantando Nginx Load Balancer..." -ForegroundColor Yellow
docker-compose -f docker-compose-full.yml up -d nginx

Write-Host "   Esperando 5 segundos..." -ForegroundColor Gray
Start-Sleep -Seconds 5

# Verificacion
Write-Host ""
Write-Host "=== Estado del Cluster ===" -ForegroundColor Cyan
docker-compose -f docker-compose-full.yml ps

Write-Host ""
Write-Host "=== Verificando servicios ===" -ForegroundColor Cyan

$services = @(
    @{Name="Nginx"; Url="http://localhost/nginx/health"},
    @{Name="Search"; Url="http://localhost/health"},
    @{Name="Ingestion"; Url="http://localhost/ingestion/health"},
    @{Name="Indexer"; Url="http://localhost/indexer/status"}
)

foreach ($svc in $services) {
    try {
        $null = Invoke-RestMethod -Uri $svc.Url -Method Get -TimeoutSec 5
        Write-Host "  $($svc.Name): OK" -ForegroundColor Green
    } catch {
        Write-Host "  $($svc.Name): FAILED" -ForegroundColor Red
    }
}

Write-Host ""
Write-Host "Cluster listo! Accede a http://localhost" -ForegroundColor Green
