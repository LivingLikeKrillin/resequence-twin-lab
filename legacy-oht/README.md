# 반도체 물류(OHT) 레거시 코드베이스 격리 안내

> **⚠️ 레거시 격리 공지 (Quarantined Legacy Codebase)**  
> 본 디렉토리는 `resequence-twin-lab` 초기 기획 단계에서 구현되었던 **반도체 OHT(Overhead Hoist Transport) 라우팅 및 교착 상태(Deadlock) 회피 도메인 코드**를 보존하고 있는 격리 보관소입니다.

본 프로젝트는 시뮬레이션의 도메인 현실성과 반증 가능성을 극대화하기 위해 완성차 제조 공정의 **도장 차체 저장소(PBS, Painted Body Store) 재시퀀싱 시나리오로 전면 재정박(Re-anchored, rev3)**되었습니다.  
이에 따라 본 디렉토리 내의 OHT 라우팅 코드는 현재 정규 Maven 빌드 및 런타임 제어 루프에서 완전히 제외되었으며, 아키텍처 의사결정 기록은 [ADR-001](../docs/adr/ADR-001-routing-solver.md)에서 효력 상실(Superseded)로 명세되었습니다.
