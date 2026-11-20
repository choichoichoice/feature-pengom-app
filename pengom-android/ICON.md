# 앱 아이콘 (Adaptive Icon)

안드로이드 8.0 이상의 런처 아이콘은 **배경 레이어**와 **앞 그림 레이어**를 따로 두고, 런처가 원·둥근 사각형 같은 마스크로 잘라서 보여준다.

```
108dp 전체 캔버스
├─ 런처가 보여주는 부분: 가운데 72dp
└─ 안전 영역: 가운데 지름 66dp 원 ← 그림이 전부 이 안에 있어야 어떤 마스크에서도 안 잘림
```

## 파일

| 파일 | 역할 |
|---|---|
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | 아이콘 정의 (배경 + 앞 그림 + 단색) |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | 둥근 아이콘을 따로 찾는 런처용 (내용 같음) |
| `app/src/main/res/drawable/ic_launcher_background.xml` | 배경 레이어: 연한 하늘색 그라데이션 (108dp 전체를 채움) |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | 앞 그림 레이어: 그림을 사방 **28dp** 안쪽에 배치 |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Android 13+ 테마 아이콘(단색)용 |
| `app/src/main/res/drawable-nodpi/ic_launcher_art.png` | **그림 원본** (투명 배경, 정사각형) |

## 새 아이콘 그림으로 바꾸는 법

1. **투명 배경의 정사각형 PNG**를 준비한다 (권장 432×432px 이상).
   - 배경색은 넣지 않는다. 배경은 `ic_launcher_background.xml`이 따로 그린다.
   - 그림을 정사각형 가장자리까지 꽉 채워도 된다. 여백은 아래 inset이 만든다.
2. `app/src/main/res/drawable-nodpi/ic_launcher_art.png`를 새 파일로 덮어쓴다 (파일 이름 그대로).
3. 그림 모양이 크게 달라졌다면 inset 값을 다시 계산한다 (아래).
4. 배경색을 바꾸려면 `ic_launcher_background.xml`의 `startColor`, `endColor`를 고친다.

## inset 값 계산

그림 중 중심에서 가장 먼 점이 33dp(안전 원 반지름) 안에 오도록 정한다.
현재 그림은 28dp다. 새 그림이면 저장소 루트에서 아래를 실행해 나온 값을
`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`의 `android:inset`에 넣는다.

```bash
python3 -c "
from PIL import Image; import math
im=Image.open('pengom-android/app/src/main/res/drawable-nodpi/ic_launcher_art.png').convert('RGBA')
a=im.split()[3].load(); W=im.width; c=(W-1)/2
r=max(math.hypot(x-c,y-c) for y in range(W) for x in range(W) if a[x,y]>40)/(W/2)
print('inset =', math.ceil((108-2*33/r)/2), 'dp')"
```

- 그림이 동그란 모양에 가까울수록 inset이 작아지고(그림이 커지고), 네 모서리까지 꽉 찬 그림일수록 inset이 커진다.
- 사각형 마스크 런처에서 그림이 너무 작아 보이면, 그림을 동그란 실루엣에 가깝게 다듬는 게 inset을 줄이는 것보다 안전하다.
