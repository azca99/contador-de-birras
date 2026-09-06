# ROADMAP - Contador de Birras

## Estado actual
Versión estable: v1.0.1

Base estable con:
- registro;
- historial;
- edición y borrado;
- sync bidireccional Room ↔ Firestore;
- restauración;
- fotos;
- ubicación privada;
- amigos;
- grupos;
- `sharedBeers` sanitizado.

## Próxima gran fase: diseño visual
Esta será la primera fase al retomar el desarrollo y va ANTES de logros.

Pendientes:
- auditoría UI;
- dirección visual;
- guía de estilos;
- tokens;
- componentes Compose reutilizables;
- estados loading/error/empty;
- accesibilidad;
- light/dark;
- coherencia de navegación.

## Logros
Pendientes:
- evaluación por ID exacto;
- estadísticas faltantes;
- logros imposibles/incorrectos;
- migración/reset/versionado;
- CLAIMED;
- progreso de nivel;
- cola de desbloqueos simultáneos;
- iconKey;
- tests table-driven.

## Sync avanzado/offline
Futuro condicionado al uso real:
- WorkManager;
- retries;
- constraints;
- sync periódico;
- observabilidad;
- pruebas multi-dispositivo.

## Calidad y automatización
Pendientes:
- GitHub Actions;
- build/test/lint automáticos;
- Firebase Rules tests;
- migration tests;
- integración reinstalación/segundo dispositivo;
- cobertura;
- crash reporting si algún día interesa.

## Mejoras pequeñas conocidas
Pendientes:
- revisar que si la compresión de imagen devuelve null no quede `photoSource` sin foto;
- pequeños textos/copy.

## Producto futuro
Backlog no prioritario:
- estadísticas avanzadas;
- mejoras sociales;
- onboarding;
- perfil;
- nuevas visualizaciones.

## Checklist para retomar
1. revisar entorno/dependencias;
2. ejecutar build/tests;
3. comprobar Firebase;
4. empezar por diseño visual;
5. después logros;
6. después nuevas features.
