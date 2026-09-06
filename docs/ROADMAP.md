# ROADMAP - Contador de Birras

## Estado actual
Esta versin v1.0.0 se considera una base estable. Los flujos principales y crticos del proyecto estn plenamente operativos, seguros y robustos:
- Registro de cervezas (alta, edicin, borrado).
- Historial.
- Sincronizacin bidireccional fiable con Firebase (Room <-> Firestore).
- Restauracin completa de datos tras una reinstalacin del dispositivo.
- Soporte para fotos subidas a Firebase Storage.
- Ubicacin guardada con privacidad mediante fallback si falla el GPS.
- Sistema de amigos y grupos para compartir.
- Seguridad social consolidada mediante `sharedBeers` sanitizado (backend Firestore Rules + Functions).

## Prxima gran fase: diseo visual
La prxima gran iteracin del proyecto deber centrarse en una auditora completa de UI, antes de seguir aadiendo lgicas complejas (como los logros).
* Definicin de una direccin visual coherente.
* Creacin de una gua de estilos y tokens (color, tipografa, spacing, shapes, elevation).
* Estandarizacin de componentes Compose reutilizables.
* Estados completos de interfaz: loading, error, empty states.
* Accesibilidad y soporte Light/Dark mode.
* Coherencia general en navegacin y transiciones entre pantallas.
* *Nota:* Abordar esta fase ANTES de continuar el desarrollo profundo de Logros.

## Logros
El sistema de logros queda conscientemente pendiente de una revisin profunda. Existen deudas tcnicas y funcionales a solventar en futuras versiones:
- La evaluacin debe hacerse por ID exacto de logro (`achievement ID`) y no por aproximaciones o prefijos.
- Necesidad de incorporar estadsticas internas, actualmente no disponibles para ciertos logros.
- Revisin de logros imposibles o con semntica incorrecta.
- Planificar una migracin/reset/versionado seguro ante desbloqueos histricos incorrectos en producción.
- Simplificacin o definicin real del estado CLAIMED.
- Clculo correcto del progreso de nivel.
- Implementacin de una cola (queue) para mostrar mltiples desbloqueos simultneos.
- Uso real y consistente del `iconKey`.
- Creacin de tests rigurosos (table-driven tests) por cada logro individual.

## Sync avanzado / offline
La base actual es funcionalmente estable. Las siguientes mejoras slo deben abordarse si el uso real del producto demuestra que son estrictamente necesarias:
- Migracin a `WorkManager` para sincronizacin robusta desatendida.
- Retries y constraints de conectividad automticos en background.
- Sincronizacin peridica programada.
- Mejor observabilidad, trazabilidad y manejo global de errores de red.
- Pruebas reales multi-dispositivo y de concurrencia intensa.

## Calidad y automatizacin
Pendientes tcnicos razonables para asegurar la salud del proyecto a largo plazo:
- Integracin Continua (CI) en GitHub Actions.
- Build, test y lint automticos en cada Pull Request.
- Tests automticos para Firebase Rules.
- Pruebas de migracin de base de datos ms exhaustivas.
- Tests de integracin (simulando reinstalacin y uso en un segundo dispositivo).
- Cobertura adicional para repositorios de datos.
- Considerar integrar Crash Reporting (ej. Firebase Crashlytics) para observabilidad en produccin.

## Mejoras pequeas conocidas
Ajustes menores y "edge cases" detectados durante la consolidacin de esta versin:
- Revisar que si la compresin de una foto falla (y devuelve `null`), el `photoSource` no quede errneamente marcado si la foto no lleg a capturarse ni guardarse.
- Limpieza y repaso de pequeos textos/copy y traducciones.

## Producto futuro
Backlog de funcionalidades no prioritarias (mejoras de producto):
- Estadsticas ms avanzadas y visualizaciones de hbitos.
- Mejoras y expansin del mdulo social.
- Onboarding interactivo para nuevos usuarios.
- Refinamientos del panel de perfil.
- Nuevas formas de visualizar la actividad histrica (p.ej. calendarios, mapas de calor).

## Checklist para retomar el desarrollo
Para retomar este proyecto despus de la pausa:
1. Actualizar dependencias del proyecto slo si es estrictamente necesario (Gradle, Compose, Firebase).
2. Ejecutar build y tests (`testDebugUnitTest`, `assembleDebug`) para verificar que el entorno compila.
3. Revisar que la configuracin de Firebase (Rules, Functions) sigue correctamente desplegada y activa.
4. Comenzar abordando la **auditora visual y rediseo de UI**.
5. Despus, abordar el rediseo profundo del sistema de **Logros**.
6. Finalmente, desarrollar nuevas features y expansiones de producto.
