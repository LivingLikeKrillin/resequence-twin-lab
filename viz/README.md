# PBS 트윈 시각화 엔진 — OpenUSD 익스포터 및 CPU 2D 스키매틱 렌더러 (`viz/`)

동일한 도장 차체 저장소(PBS) 궤적 데이터(`GET /api/trajectory`)를 기반으로 고사양 3D 씬과 경량 2D 애니메이션을 상호 보완적으로 생성하는 시각화 도구 모음입니다:

| 산출물 | 생성 도구 | 요구 환경 | 시각화 내용 및 특징 |
|---|---|---|---|
| `pbs_twin.usda` | `viz/export.py` | USD 지원 뷰어 (usdview / Omniverse / Blender) | **3D 디지털 트윈 씬**: 실제 물리적 크기 기반 씬 그래프 및 시간 샘플링 애니메이션 |
| `pbs_twin.gif` | `viz/render.py` | Python + Matplotlib (순수 CPU, GPU 불필요) | **단일 2D 스키매틱**: 버퍼 점유 및 불출 서열의 시간별 전진 동역학 |
| `pbs_compare.gif` | `viz/compare.py` | Python + Matplotlib (순수 CPU, GPU 불필요) | **정적 vs 동적 비교 스키매틱**: 동일 시드 상의 서열화 품질 격차 실시간 시각 대조 |

---

## 1. 핵심 차별화 한눈에 보기 — 정책 비교 애니메이션

다목적 재시퀀싱 최적화의 효과를 가장 직관적으로 확인하는 방법은 상하 2분할 비교 애니메이션(`pbs_compare.gif`)입니다:
- **상단 패널**: 정적 기준선 정책 (라운드로빈 레인 배정 + 납기 FIFO 방출)
- **하단 패널**: 동적 다목적 정책 (가중치 기반 색상/납기/옵션 최적화 + 기아 방지)
- 동일한 무작위 시드와 차체 스트림에 대해 프레임 단위로 동기화되어 진행되며, 각 패널 상단에 누적 색상 변경 횟수 카운터가 실시간으로 갱신되어 품질 격차를 즉각 증명합니다.

```bash
# 1. 실행 중인 제어 서비스로부터 실시간 생성 (기본 포트 8081):
python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif

# 2. 사전에 저장된 JSON 궤적 파일로부터 완전 오프라인 생성:
python -m viz.compare --static-file static.json --dynamic-file dynamic.json --out pbs_compare.gif

# 3. 제어 서비스 엔드포인트 URL 지정:
RESEQUENCE_TWIN_CONTROL_URL=http://localhost:8081 python -m viz.compare --seed 42 --bodies 100 --out pbs_compare.gif
```

> **스키매틱 안내**: 본 GIF 파일은 GPU 렌더링이 아닌 경량 2D 도식화 그래픽이며, 표시되는 색상 변경 수는 방출 서열로부터 실시간 산출된 실제 KPI 수치입니다.

---

## 2. 세부 도구별 엔지니어링 아키텍처

### ① 3D OpenUSD 익스포터 (`viz/export.py`)
- 제어 서비스의 `GET /api/trajectory` REST 엔드포인트 또는 로컬 JSON 파일로부터 스텝별 궤적 데이터를 파싱합니다.
- 표준 OpenUSD(`.usda`) 씬을 구성합니다:
  - 바닥 그리드(Ground plane) 및 레인 가이드 박스
  - 차량 규격에 맞춘 차체 지오메트리 (`UsdGeom.Cube`, $4 \times 1.5 \times 2\,\text{m}$)
  - 도장 색상을 표현하는 `displayColor` 프리미티브 변수(Primvar)
  - 차체 인입 → 레인 적재 → 조립 라인 방출에 이르는 시간 샘플링(Time-sampled) 변환 및 가시성(Visibility) 애니메이션
- 복잡한 셰이더 네트워크 대신 표준 `displayColor`를 채택하여 고사양 GPU 없이도 **usdview**(OpenGL/Storm), Blender 3.x+, NVIDIA Omniverse에서 즉시 로드 가능합니다.

### ② 무헤드 CPU 2D 스키매틱 렌더러 (`viz/render.py`)
- 디스플레이나 GPU가 없는 환경(CI/헤드리스 서버)을 위해 Matplotlib Agg 백엔드로 단일 트윈 애니메이션 GIF를 생성합니다.
- 프레임별 레이아웃 (Top-down 뷰):
  - **레인 행 (`L1`, `L2`, `L3`)**: 슬롯별 차체의 도장 색상 채색, 방출 선두 방향 화살표 표시, 해당 스텝에서 방출된 차체에 굵은 주황색 테두리 하이라이트.
  - **조립 배출 레일 (Assembly Rail)**: 방출된 차체가 좌에서 우로 누적 전진하며 최종 재시퀀싱 서열을 시각화.
  - **상단 색상 스트립 바**: 도장 인입 순서 vs 현재까지의 조립 방출 순서를 대조하여 색상 배치가 정렬되는 과정을 시각화.

### ③ 정적 vs 동적 정책 비교 렌더러 (`viz/compare.py`)
- 두 개의 궤적 JSON(정적 정책, 동적 정책)을 입력받아 단일 GIF 상하 패널로 렌더링합니다.
- 시간 축이 완벽히 동기화되어 있으며, 각 패널의 실시간 색상 변경 KPI 카운터를 통해 알고리즘 성능 차이를 명확하게 비교합니다.

---

## 3. 설치 및 실행 가이드

### 의존성 설치
```bash
cd viz
python -m pip install -e .[dev]
```
주요 라이브러리: `usd-core>=24.05`, `matplotlib>=3.8`, `pillow>=10.0`, `numpy>=1.26`.

### 3D OpenUSD 익스포트
```bash
# 실행 중인 제어 서비스로부터 익스포트
python -m viz.export --seed 42 --bodies 100 --policy dynamic --out pbs_twin.usda

# 로컬 JSON 파일로부터 익스포트
python -m viz.export --from-file trajectory.json --out pbs_twin.usda
```

### 단일 트윈 2D 스키매틱 렌더링
```bash
python -m viz.render --seed 42 --bodies 100 --policy dynamic --out pbs_twin.gif --fps 4 --dpi 96
```

### 3D 씬 뷰어 확인 방법
1. **usdview (권장, 저용량 VRAM 친화적)**:
   ```bash
   usdview pbs_twin.usda
   ```
   `usd-core`에 포함되어 있으며 Space 키를 눌러 애니메이션을 재생할 수 있습니다.
2. **Blender**:
   `File > Import > Universal Scene Description (.usd)` 메뉴를 통해 로드.
3. **NVIDIA Omniverse**:
   USD Composer / Kit에서 `pbs_twin.usda`를 오픈하여 RTX 레이트레이싱 렌더링 적용 가능.

### 단위 테스트 실행 (완전 오프라인)
```bash
cd viz
python -m pytest -q
```
모든 테스트는 디스플레이가 필요 없는 무헤드 환경에서 동작하며 85개 테스트가 전수 통과합니다.

---

## 4. 정직성 안내 (Honesty Note)

본 시각화 파이프라인에서 소비되는 모든 궤적 데이터는 절차적으로 생성된 **가상 합성 데이터**입니다. OpenUSD 익스포터는 산업 표준 3D 포맷으로의 파이프라인 연계 가능성을 입증하기 위한 도구이며, 2D 스키매틱 GIF는 GPU가 없는 환경에서도 서열화 제어 로직의 결과를 명확하게 관측할 수 있도록 지원하는 정직한 공학적 보조 도구입니다.

