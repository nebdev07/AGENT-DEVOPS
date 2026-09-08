# Lab local — faux VPS Ubuntu

> Substitution du VPS-2 **sur ta machine**, quand Docker est installé.  
> Ce n’est **pas** la prod ARC, **pas** le staging DTSI.

Ce PC (2026-09-07) n’avait **ni Docker ni WSL**. Sans l’un des deux, le lab ne démarre pas : la console utilise alors la **fixture** (Ubuntu vierge 4 vCPU / 8 Go / 75 Go), même contrat JSON que le SSH réel.

## Quand Docker est là

```powershell
powershell -File scripts/lab-up.ps1
```

Puis dans `.env` :

```
DEVOPS_SSH_HOST=127.0.0.1
DEVOPS_SSH_PORT=2222
DEVOPS_SSH_USER=lab
DEVOPS_SSH_KEY=infrastructure/lab/keys/lab
DEVOPS_SSH_TRUST_UNKNOWN=true
```

`TRUST_UNKNOWN=true` **uniquement** pour ce loopback. Jamais pour un VPS public.

## Vers la console

```powershell
$env:JAVA_HOME = "C:\env\java\jdk-21"
mvn -q spring-boot:run
```

Ouvrir `http://127.0.0.1:8088/` → **Analyser le serveur**.
