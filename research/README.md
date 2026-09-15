# 도메인 연구 및 공학적 근거 (Research & Grounding)

> **엔지니어링 정직성 계약 (Honesty Contract)**  
> 본 문서에 기재된 모든 외부 정량 지표는 실제 완성차 제조 현장에서 발표된 **학술 연구 참고치(Published Reference)**이며, **본 PoC의 직접적인 실측치가 아닙니다.**  
> 본 PoC는 동일한 무작위 시드로 생성된 합성 스트림 상에서 정적 기준선 대비 동적 알고리즘의 **상대적 개선율(Relative Deltas)**만을 산출 및 보고합니다 (루트 [`README.md`](../README.md) §12 정직성 원칙 참조).

본 문서는 `resequence-twin-lab`이 도메인 모델과 최적화 목적식의 토대로 삼은 실제 산업 현장의 연구 템플릿과, 자동차 도장 차체 저장소(PBS, Painted Body Store) 재시퀀싱 문제가 지닌 실질적 공학적 가치를 기록합니다.

---

## 1. 반증 가능한 산업 템플릿 — 포드 자를루이 공장 차체 재시퀀싱 연구

**Karrenbauer, A., Kuhn, B., Mehlhorn, K., Rinaldi, P. L. (2025).**  
*Optimizing Car Resequencing on Mixed-Model Assembly Lines: Algorithm Development and Deployment.*  
arXiv:2507.17422 (제출일: 2025-07-23). [https://arxiv.org/abs/2507.17422](https://arxiv.org/abs/2507.17422)

### 본 연구가 PoC의 기준이 되는 이유
1. **문제 정의의 완벽한 일치**:  
   혼류 조립 라인(Mixed-Model Assembly Line)에서 **도장 공장의 색상 교체 최소화**, **조립 라인의 작업 부하(옵션) 평탄화**, **조립 납기(JIS) 기한 준수**라는 상충하는 3대 목표를 동시에 다루며, 이는 본 랩의 `DynamicSequencingPolicy`가 가중치를 부여하는 3대 목적식(색상 4.0 / 납기 3.0 / 옵션 2.0; [ADR-002](../docs/adr/ADR-002-sequencing-solver.md) 참조)과 구조적으로 일치합니다.
2. **실제 완성차 공장 배포 검증**:  
   독일 자를루이(Saarlouis)에 위치한 포드 공장(Ford-Werke GmbH)의 실제 생산 라인에 알고리즘을 배포한 후 4주간 기록된 이벤트 데이터를 기반으로 도출된 실증 연구로서, 장난감 문제(Toy Problem)가 아닌 산업적 실재성을 증명합니다.
3. **참고 정량 지표 (실제 공장 실측치 — 본 PoC 수치 아님)**:  
   - 평균 도장 배치 크기: **약 +30% 증가**
   - 도장 색상 교체 횟수: **−23% 감축**
   - 납기 분산: **−10% 개선**  
   본 PoC의 벤치마크 지표는 동일한 메트릭 제품군을 채택하되, 합성 100대 차체 스트림 상의 상대적 개선치로 엄격히 한정하여 보고합니다.

### 산업 논문 대비 본 PoC의 정직한 엔지니어링 대비

| 평가 지표 (KPI) | 포드 자를루이 공장 논문 (발표치) | 본 PoC 실측치 (합성 데이터, 상대 델타) |
|---|---|---|
| **도장 색상 교체** | −23% 감축 | **HARD 불변식**: 7개 강건성 시드 전수에서 정적 대비 동적 정책이 우수 (`dynamic <= static`) |
| **평균 배치 길이** | +30% 증가 | **HARD 불변식**: 7개 강건성 시드 전수에서 정적 대비 동적 정책이 우수 (`dynamic >= static`) |
| **납기 편차** | −10% 개선 | **문서화된 관측 특성 (Observation)**: 대다수 시드에서 개선되나, 색상 가중치(`W_COLOR=4.0`)가 납기 가중치(`W_DUE=3.0`)를 상회할 때 일부 시드에서 JIS 순서가 지연될 수 있음 ([ADR-002](../docs/adr/ADR-002-sequencing-solver.md) OBS-3 참조) |

---

## 2. 도장 차체 저장소 (PBS, Painted Body Store) 도메인 컨텍스트

PBS는 **도장 공장(Paint Shop)**과 **의장/조립 공장(Trim & Final Assembly)** 사이에 위치하는 다중 FIFO 레인 버퍼 공간입니다:
- **도장 공장**: 페인트 분사 노즐 세척 및 용제 낭비를 줄이기 위해 동일한 색상의 차체를 최대한 길게 모아서 연속 도장하기를 원합니다.
- **조립 공장**: 부품 적시 공급(JIS) 순서와 작업자의 피로도를 유발하는 고부하 옵션(선루프, 특정 전장 옵션 등)의 균등 분산을 요구합니다.

이 두 상충하는 요구사항 간의 택트 타임 불일치를 해소하기 위해 PBS 버퍼에서 차체 서열을 동적으로 재배치(Resequencing)하는 작업이 필수적입니다.  
본 랩은 프로젝트 초기 기획 단계의 반도체 OHT 라우팅 시나리오(ADR-001 참조)를 완성차 제조 현장의 현실성이 검증된 PBS 재시퀀싱 시나리오로 완전히 전환하여 구축되었습니다.

---

## 3. 검토 기술에 대한 공학적 경계 선언 (미사용 및 제한적 활용)

- **NVIDIA Omniverse / OpenUSD**:  
  초기 아키텍처 구상 단계에서 포트폴리오 차원의 3D 디지털 트윈 씬 확장 아이디어로 검토되었으나, 정규 빌드 및 런타임에는 도입되지 않았습니다. 실제 3D 씬 데이터 파이프라인은 오픈 표준 OpenUSD(`.usda`) 익스포터와 경량 CPU 2D 스키매틱 렌더러([`viz/`](../viz/README.md))로 구현되었습니다.
- **Google OR-Tools CP-SAT**:  
  실시간 방출 루프는 연산 지연시간이 극히 짧은 다목적 휴리스틱을 운영 정책으로 사용하며, CP-SAT은 런타임 제어가 아닌 **오프라인 최적성 갭 오라클([`solver/`](../solver/), [`research/optimality-gap.md`](optimality-gap.md))**로 분리 구현되어 휴리스틱의 수학적 한계를 정밀 검증하는 도구로 활용됩니다.
- **머신러닝 서열 꼬임 예측기**:  
  합성 데이터에 대한 인위적인 지도학습을 배제하고, 해석 가능한 도메인 특징 기반의 결정론적 가중 휴리스틱으로 구현되었습니다 ([ADR-003](../docs/adr/ADR-003-scramble-forecast.md)).

---

## 참고 문헌 (References)

- [arXiv:2507.17422 — Optimizing Car Resequencing on Mixed-Model Assembly Lines: Algorithm Development and Deployment](https://arxiv.org/abs/2507.17422)

