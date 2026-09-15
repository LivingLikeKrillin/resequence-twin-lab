# 아키텍처 결정 기록 색인 (Architecture Decision Records)

본 디렉토리는 `resequence-twin-lab` 프로젝트의 핵심 아키텍처 의사결정과 진화 과정을 투명하게 기록한 엔지니어링 표준 ADR 모음입니다.

---

## ADR 목록 및 현재 상태

| 문서 식별자 | 아키텍처 결정 주제 | 현재 상태 | 적용 도메인 및 핵심 결정 내용 |
|---|---|:---:|---|
| **[ADR-001](ADR-001-routing-solver.md)** | **라우팅 솔버 선정** (JGraphT Dijkstra vs OR-Tools / cuOpt) | **효력 상실 (Superseded)** | 초기 반도체 OHT 라우팅 시나리오. 완성차 PBS 도메인 재정박에 따라 레거시로 격리됨 ([`legacy-oht/`](../../legacy-oht/README.md)) |
| **[ADR-002](ADR-002-sequencing-solver.md)** | **서열화 솔버 선정** (OR-Tools CP-SAT vs 다목적 탐욕적 휴리스틱) | **채택 (Accepted)** | 런타임 방출 정책은 경량 다목적 휴리스틱(`DynamicSequencingPolicy`) 채택. CP-SAT은 최적성 갭 실측 오라클([`solver/`](../../solver/))로 구현 |
| **[ADR-003](ADR-003-scramble-forecast.md)** | **서열 꼬임 예측 엔진 선정** (투명한 도메인 휴리스틱 vs 학습형 ML 모델) | **채택 (Accepted)** | 3대 도메인 특징 기반 정규화 가중 평균 휴리스틱(`ScramblePredictor`) 채택. 설명 가능한 AI 자문 도구 제공 및 머신러닝 업그레이드 경로 명세 |

---

## 의사결정 프레임워크 및 규범

1. **시나리오 재정박(Re-anchoring)의 역사적 연속성**:  
   초기 반도체 OHT 라우팅(ADR-001)에서 자동차 PBS 재시퀀싱(ADR-002)으로의 도메인 전환 과정을 삭제하지 않고 투명하게 보존하여 아키텍처 진화의 인과관계를 추적 가능하게 합니다.
2. **설계에 의한 정직성 (Honesty by Construction)**:  
   수리적 최적해를 보장하지 못하는 휴리스틱을 최적 솔버로 위장하지 않으며, 학습 데이터가 부재한 상태에서 가중치 기반 경험식을 머신러닝 학습 모델로 포장하지 않습니다.
3. **독립적 수리 검증 체계 (Optimality Oracle)**:  
   런타임 제약(실시간성, 밀리초 단위 루프)으로 인해 채택하지 못한 엄밀한 수리 솔버(CP-SAT)를 오프라인 오라클로 분리 구현하여 휴리스틱의 품질을 독립적으로 반증 및 계측합니다.
