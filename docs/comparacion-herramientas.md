# Comparacion: GitHub Actions, Jenkins y Argo CD

**Primero:** no son tres alternativas equivalentes. GitHub Actions y Jenkins **automatizan CI/CD** (build, pruebas,
escaneos, empaquetado). Argo CD es **entrega continua GitOps sobre Kubernetes**: observa un repositorio de manifiestos y
reconcilia el cluster; complementa al CI, no lo reemplaza.

| Criterio | GitHub Actions | Jenkins | Argo CD |
|---|---|---|---|
| Funcion | CI/CD integrado al repositorio | Servidor de automatizacion CI/CD | Despliegue declarativo (GitOps) en Kubernetes |
| Facilidad de inicio | Alta: un YAML en `.github/workflows`, runners administrados | Media/baja: instalar, plugins, agentes, credenciales | Media: requiere cluster, instalar Argo CD, definir `Application` |
| Trazabilidad | Cada ejecucion ligada a commit/PR; checks en el PR; artefactos por run | Buena con plugins (Blue Ocean, Git); ligada a builds, no nativa al PR | Excelente para despliegues: historial de sync, diff deseado/real, rollback a un commit |
| Seguridad de credenciales | Secretos por repo y por **entorno** con reglas de aprobacion; `GITHUB_TOKEN` efimero y con permisos minimos; OIDC a nubes | Credential store propio; el servidor y sus plugins son superficie de ataque a mantener | Credenciales del cluster quedan dentro del cluster (pull); el CI no necesita acceso al cluster |
| Dependencia de infraestructura | Ninguna propia (runners de GitHub); cuotas del plan | Total: servidor, agentes, backups, actualizaciones | Cluster Kubernetes + Argo CD operando |
| Escala educativa (4 sprints, equipo de 2) | **Adecuada**: el esfuerzo va a gates, pruebas y despliegue | Costosa: tiempo en operar la herramienta, no el producto | Excesiva salvo que el curso provea Kubernetes |
| Costo | Gratis en repos publicos; minutos incluidos en privados | Software libre; costo de hosting y operacion | Software libre; costo del cluster |

## Como se ve en este proyecto
`push/PR` -> **GitHub Actions** (secrets-scan, build-test, contracts, SAST, SCA, package con imagen `sha-<commit>` + SBOM +
escaneo) -> **staging** por deploy hook (Render/Docker) con smoke test y rollback al ultimo tag bueno -> SAT + DAST.
Con Argo CD, el paso `deploy-staging` cambiaria a: el CI actualiza el tag de imagen en un repo de manifiestos y Argo CD
sincroniza; build y pruebas siguen en Actions.

## Decision
**GitHub Actions** como pipeline principal (implementado en `.github/workflows/ci.yml`). Jenkins solo se justificaria si
se exigiera CI propio on-premise. Argo CD se adoptaria si el despliegue fuera a Kubernetes y hubiera tiempo para operar
GitOps; para un staging de un solo servicio, el deploy hook con smoke test y rollback cubre el requisito con menos piezas.

Fuentes: docs.github.com/actions · jenkins.io/doc · argo-cd.readthedocs.io
