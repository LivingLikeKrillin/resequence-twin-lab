# ADR-002: 서열화 솔버 선정 — OR-Tools CP-SAT vs. 다목적 탐욕적 룩어헤드 휴리스틱 (Sequencing Solver Choice: CP-SAT vs Greedy Heuristic)

- **결정 일자:** 2026-06-17 (2026-06-19 오프라인 최적성 오라클 실측 결과 반영 업데이트)
- **상태:** 채택 (Accepted) — [2026-06-19 업데이트](#update-2026-06-19-오프라인-최적성-갭-오라클로-구현된-cp-sat-실측치): CP-SAT은 런타임 제어 정책 교체가 아닌 **오프라인 최적성 갭 오라클(Offline Optimality Oracle)**로 완전히 구현되어 휴리스틱의 수학적 최적 근접성을 증명하는 도구로 활용됨.
- **도메인 컨텍스트:** 도장 차체 저장소(PBS) 다목적 재시퀀싱 PoC (rev3 R2)

---

## 1. 배경 및 당면 과제 (Context)

PBS 버퍼의 실시간 불출 제어를 담당하는 `DynamicSequencingPolicy`는 차체를 조립 라인으로 방출할 때마다 상충하는 3가지 공학적 목표를 동시에 최적화해야 합니다:

1. **도장 색상 배치 확장 (Colour-batch extension)**: 도장 공장의 색상 교체(Color switch)로 인한 퍼지(Flush) 비용과 유휴 시간을 최소화하기 위해 동일 색상 차체를 연속 배치 (포드 자를루이 공장 학술 논문에서 가장 지배적인 목적식).
2. **조립 옵션 평탄화 (Option leveling)**: 선루프 등 고부하 작업이 요구되는 특정 옵션 차체가 연속 투입되어 조립 라인이 지연되는 병목 현상을 방지하기 위해 균등 분산.
3. **조립 납기 및 서열 준수 (Due-date / JIS sequence adherence)**: 차체별 JIS(Just-In-Sequence) 서열 번호를 준수하며, 특정 차체가 버퍼 내에 무기한 갇히는 기아 현상(Starvation)을 원천 차단.

본 설계에서는 이를 해결하기 위해 두 가지 솔버 접근 방식을 종합적으로 평가하였습니다.

---

## 2. 의사결정 사항 (Decision)

개념 증명(PoC) 런타임 제어 엔진으로는 **다목적 탐욕적 룩어헤드 휴리스틱 (Greedy Lookahead Heuristic)**을 채택하여 구현하였습니다.  
이와 동시에 산업 표준 수리 최적화 솔버인 **Google OR-Tools CP-SAT**은 운영 환경 업그레이드 경로(Upgrade path)로 명세되었으며, 후속 연구를 통해 **오프라인 최적성 갭 오라클(`solver/`)**로 실제 구현되어 휴리스틱의 이론적 최적 근접성을 반증 가능하게 입증하였습니다.

---

## 3. 대안 A — Google OR-Tools CP-SAT (운영 스택 적합성)

### 적합성 및 기술적 강점
- CP-SAT(Constraint Programming - Satisfiability)은 가중치 기반 다목적 함수, 하드 제약식, 시간 윈도우 룩어헤드 조합 최적화를 완벽하게 지원합니다.
- PBS 서열화는 순서 의존적 스케줄링(Sequence-dependent scheduling) 문제의 전형적인 변형이므로, 수학적으로 CP-SAT이 가장 이상적인 솔버입니다.
- 제한된 룩어헤드 윈도우 내에서 수학적으로 증명된 엄격한 최적해(Optimal sequence)를 도출할 수 있습니다.

### PoC 런타임에 직접 탑재하지 않은 사유
- **연산 복잡도 및 실시간성 제약**: 엄격한 조합 최적화(CP-SAT)는 차체 수가 증가함에 따라 지수 함수적(Exponential) 연산 시간이 소요되므로, 1초 미만의 주기적인 방출 결정을 내려야 하는 밀리초(ms) 단위 런타임 제어 루프에 직접 배치하기 부적합합니다.
- **플랫폼 의존성 및 JNI 이슈**: OR-Tools Java(`com.google.ortools:ortools-java`)는 운영체제별 네이티브 바이너리(`.dll`, `.so`)를 포함하므로, Windows 환경 및 다양한 CI 환경에서 JNI 링크 실패로 인해 빌드 무결성을 저해할 위험이 있었습니다.
- **핵심 차별화 가치**: 본 프로젝트의 본질적 차별화는 특정 솔버 엔진 자체가 아니라 **다목적 재시퀀싱 제어 로직과 정적 vs 동적 정책 간의 반증 가능한 인과성 증명**에 있습니다.

### 향후 업그레이드 경로 (Upgrade Path)
제어 서비스의 `DynamicSequencingPolicy.score()` 내부 연산을 CP-SAT 모델 호출(Java 네이티브 또는 Python gRPC 서브프로세스)로 교체할 수 있습니다. `SequencingPolicy` 인터페이스 계약과 상위 벤치마크 하네스는 일체 변경 없이 유지됩니다.

---

## 4. 대안 B — 다목적 탐욕적 룩어헤드 휴리스틱 (채택된 PoC 구현체)

### 휴리스틱 스코어링 수식 (Scoring Formula)
각 레인의 최전선(Lane-front)에 위치한 방출 후보 차체 $b$에 대해 다음과 같이 가중 합산 복합 점수를 산출합니다:

```
score(b) = W_COLOR  * colorBatchBonus(b)
         + W_OPTION * optionLevelingBonus(b)
         + W_DUE    * dueDateBonus(b)
```

| 가중치 상수 | 설정값 | 공학적 목적 및 가중치 사유 |
|---|:---:|---|
| `W_COLOR` | 4.0 | 도장 색상 배치 확장 가중치 (`colour batch weight`) — 산업 문헌상 가장 큰 비용 절감 동인 |
| `W_DUE` | 3.0 | 납기 서열 긴급도 근접 가중치 (`due-date urgency`) — JIS 납기 윈도우 준수 |
| `W_OPTION` | 2.0 | 조립 작업 부하 평탄화 가중치 (`option leveling`) — 고부하 차체 연속 투입 방지 |

### 하드 불변 제약식: 기아 방지 오버라이드 (Anti-Starvation Hard Constraint)
차체의 납기 시퀀스 번호가 현재 조립 라인 누적 방출량 이하인 경우(`dueDateSeq <= assemblyOutSize`, 즉 납기 초과 차체), 휴리스틱 스코어링을 즉시 중단하고 해당 차체를 최우선 방출합니다. 이를 통해 색상 배치를 연장하려는 최적화 목적이 납기가 도래한 차체를 무기한 지연시키는 기아 현상을 원천 방지합니다.

### 결정론적 동작 보장 (Determinism)
난수 발생기(`Math.random()`)나 시스템 시계(`System.currentTimeMillis()`)에 의존하지 않으며, 동점 발생 시 레인 등록 순서로 타이브레이킹을 수행합니다. 동일한 `PbsState` 입력에 대해 항상 100% 동일한 출력을 보장하여 R3 회귀 테스트의 완벽한 재현성을 제공합니다.

---

## 5. 업데이트 (2026-06-19): 오프라인 최적성 갭 오라클로 구현된 CP-SAT 실측치

> **상태 변경 안내**: 초기의 "Java 네이티브 문제로 인한 보류" 프레임은 완전히 해소되었습니다. ADR-002에서 예고했던 Python 서브프로세스 기반 OR-Tools CP-SAT 모델이 최상위 [`solver/`](../../solver/) 모듈의 **오프라인 최적성 갭 오라클(Offline Optimality Oracle)**로 완전히 구현되었습니다.

### 주요 의의 및 아키텍처 역할
런타임 방출 정책은 초경량 `DynamicSequencingPolicy` 휴리스틱을 그대로 유지합니다. 오프라인 오라클은 검증용 고정 픽스처(Fixture)를 대상으로 CP-SAT 수리 모델을 실행하여 **수학적으로 증명된 K-FIFO 최소 색상 변경 최적해와 휴리스틱 간의 최적성 갭(Optimality Gap)을 정밀 측정**합니다.

### 오라클 수리 모델 설계 (Mathematical Formulation)
오라클은 도착 순서대로 차체가 인입되고 FIFO 레인(용량 $cap_l$)을 거쳐 방출되는 K-FIFO 버퍼 동역학을 정밀하게 모형화합니다:
- **용량 준수 시간 완화 (Capacity-Respecting Relaxation)**: 시뮬레이터의 즉시 인입(ASAP admission) 규칙을 강제하지 않고 인입 타이밍을 자유 변수로 완화함으로써, 오라클의 최적해는 항상 시뮬레이터 규칙 기반 최적해보다 작거나 같게 됩니다 ($\text{optimal}_{\text{relaxed}} \le \text{optimal}_{\text{greedy}} \le \text{heuristic}$). 따라서 도출된 최적성 갭은 **보수적 상한선(Conservative upper bound)**이 됩니다.
- **납기 제약 하드 바운드 (Due-Date Hard Constraint)**: 인입 스트림이 이미 부분 색상 배치되어 있으므로, 납기 제약이 없으면 이론적 최소 색상 변경 수는 자명하게 $\text{색상수} - 1$이 됩니다. 따라서 휴리스틱이 달성한 납기 편차를 하드 상한선으로 강제합니다:
  $$\sum_i |\text{pos}[i] - \text{dueDateSeq}[i]| \le \text{round}(N \cdot \text{heuristic.dueDateDeviation})$$
  이를 통해 오라클의 최적해는 **"휴리스틱이 달성한 동일 납기 준수 수준에서 낭비된 도장 색상 변경 횟수"**를 측정하는 공정한 파레토(Pareto) 비교 기준이 됩니다.
- **수학적 무결성**: 휴리스틱의 실행 경로 자체가 해당 제약식의 실행 가능해(Feasible solution)이므로, $\text{gap} = \text{heuristic.colorChanges} - \text{optimal} \ge 0$ 원칙이 구조적으로 성립합니다.

### 실측 벤치마크 결과 ($N=12$, 3개 레인 × 용량 1, 시드 5종, 10초 내 최적성 증명 완료)

| 시드 (Seed) | 비제약 색상 최적치 | 납기 제약 최적치 | 휴리스틱 색상 변경수 | 최적성 갭 (Gap) | 수학적 증명 여부 |
|:---:|:---:|:---:|:---:|:---:|:---:|
| 1 | 2 | 3 | 3 | **0** | 증명 완료 (Proven) |
| 7 | 1 | 1 | 3 | **2** | 증명 완료 (Proven) |
| 42 | 2 | 3 | 3 | **0** | 증명 완료 (Proven) |
| 99 | 2 | 3 | 3 | **0** | 증명 완료 (Proven) |
| 2024 | 2 | 3 | 5 | **2** | 증명 완료 (Proven) |

**실측 요약**:
- 동적 휴리스틱은 증명된 납기 제약 최적해 대비 **0~2회 이내의 극소 오차**만을 기록하였습니다.
- **5개 시드 중 3개 시드에서 최적성 갭 0 (완전 파레토 최적)**을 달성하였습니다.
- 회귀 테스트 상한선(Ceiling)은 최대 갭 2에 여유분 1을 더한 **3**으로 설정되어 지속 검증됩니다.

---

## 6. 파급 효과 및 불변식 분류 (Consequences & Invariants)

- **정적 vs 동적 정책의 명확한 분리**: 두 정책 모두 동일한 `SequencingPolicy` 인터페이스를 구현하므로 상위 벤치마크 하네스에서 투명하게 교체 평가됩니다.
- **회귀 테스트 불변식의 정직한 분류**:
  - `dynamic.colorChanges <= static.colorChanges`: **HARD 불변식** (7개 강건성 시드 전수 통과)
  - `dynamic.batchLength >= static.batchLength`: **HARD 불변식** (7개 강건성 시드 전수 통과)
  - `dynamic.dueDateDeviation <= static.dueDateDeviation`: **문서화된 관측 특성 (Observation)**. 색상 가중치(`W_COLOR=4.0`)가 납기 가중치(`W_DUE=3.0`)보다 높기 때문에 특정 시드(예: 시드 99)에서는 색상 배치를 위해 JIS 순서가 일부 희생될 수 있습니다. 이는 단일 스칼라 가중치 휴리스틱의 고유한 트레이드오프이며 `PbsBenchRegressionTest.OBS-3`에서 정직하게 검증됩니다.
- **엔지니어링 정직성 원칙**: 본 구현체는 어디까지나 경량 다목적 휴리스틱이며, 포드 자를루이 공장 수치(+30% 배치, −23% 색상 변경)는 산업 현장의 실재성을 입증하는 학술 참고문헌(arXiv:2507.17422)으로 명확히 표기됩니다.

