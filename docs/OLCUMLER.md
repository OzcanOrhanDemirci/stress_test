# Ölçümler

Her fazın telefonda ölçülen sonuçları. Burada yazmayan bir sayı ölçülmemiştir.
Cihaz: Honor 400 (DNY-NX9), seri `AKSC025610001388`, Android 16.

## Faz 0 · Ölçüm aracı

### Uygulama neyi okuyabiliyor (2026-10-01, release derleme, tanılama ekranı)

| Veri | Uygulama | Not |
|---|---|---|
| Çekirdek frekansı, 3 küme (`policy0/4/7`) | ✅ | |
| Sıcaklık: A715 (8/8 bölge), A510 (4/4), GPU (2/2), DDR (1/1), NPU (3/3), modem (4/4), kamera (1/1) | ✅ | Planın en büyük riski yoktu: hepsi açık |
| GPU meşgul sayacı (`kgsl-3d0/gpubusy`) | ✅ | GPU boştayken `0 0` okunur, oran yok |
| Pil `CURRENT_NOW` | ✅ | **1 Hz'de güncelleniyor** (10 Hz örnekte medyan değişim aralığı 1,000 sn) |
| Pil gerilimi | yalnız pil yayınıyla | pilde **~30 sn'de bir** güncelleniyor (medyan 30,7 sn) |
| Şarj sayacı (`CHARGE_COUNTER`) | ✅ ama **mAh** | Android µAh ister, Honor mAh veriyor (5679 @ %97 → tam ≈ 5,86 Ah); ~30 sn'de bir güncelleniyor |
| Termal pay (`getThermalHeadroom`) | ✅ | boşta 0,63 |

### Qualcomm `core_ctl` boştaki büyük çekirdekleri duraklatıyor

Boştayken ölçüldü (`/sys/devices/system/cpu/cpu4/core_ctl/global_state`): **CPU 5 ve CPU 7 duraklatılmış**.
A715 kümesinde `min_cpus=2`, prime'da `min_cpus=0`. Duraklatılmış bir çekirdeğe `sched_setaffinity` başarısız oluyor.
Cihaz testi bunu yakaladı (C9, CPU 7'ye sabitlenemedi). **Önlem:** yakıcı iş parçacığı sabitlenene kadar her paketten sonra
yeniden deniyor. Yük talebi `core_ctl`'i çekirdeği açmaya zorluyor. Test: `CpuEngineDeviceTest`, sekiz iş parçacığı 3 sn içinde
sabitlendi.

### İlk uçtan uca koşu (kablo takılıyken, güç geçersiz)

`fp32_gemm` her çekirdekte, 20 sn:

| Küme | İş | Çekirdek başına | Döngü başına | Tepeye oran |
|---|---|---|---|---|
| A510 ×4 @1,80 GHz | 46,0 GFLOPS | 11,5 | 6,4 FLOP | tepe değeri henüz bilinmiyor |
| A715 ×3 @2,40 GHz | 111,2 GFLOPS | 37,1 | 15,4 FLOP | %96 (2×128 bit FMA = 16 FLOP/döngü) |
| A715 prime @2,63 GHz | 38,5 GFLOPS | 38,5 | 14,6 FLOP | %91 |

Frekanslar 20 sn boyunca tavanda kaldı. Hesap hatası 0. 20 sn sonunda en sıcak bölge A510 (64,9 °C).

### Pilde güç ölçümü (2026-10-01 gecesi, oturum `20261001-010903`)

- `CURRENT_NOW`: **mA**, deşarjda **eksi**, **1 Hz** güncelleniyor. Uygulama birimi ve işareti boşta evresinden kendisi çıkarıyor (µA/mA ayrımı büyüklükten).
- Gerilim yalnız pil yayınıyla ~30 sn'de bir geliyor: yüke girişin ilk ~30 sn'sinde güç, boştaki (daha yüksek) gerilimle hesaplanıyor.
  Boşta 4,395 V, yükte 4,302 V → ilk 30 sn en çok ~%2 fazla. Bütün adaylara aynı biçimde etki ediyor, sıralamayı değiştirmez.
- Şarj sayacıyla çapraz kontrol bu oturumda **yapılamadı**: sayaç mAh ve 30 sn adımlı; 70 sn'lik pencerede 2-3 adım var. Kontrol için
  10 dk'lık kararlı bir yük gerekiyor (yapılacak). Sayaç birimi düzeltildi (mAh → µAh, `Power.normalizeChargeCounter`).

## Faz 1 · CPU aday taraması

Oturum `20261001-010903`: 11 yük × 2 tekrar, karışık sıra, her koşu 10 sn boşta + 60 sn yük, başlangıçta CPU ≤ 42 °C,
**performans modu açık** (`sys.aps.power_mode=3`), ekran parlaklığı 0,2, pil %100 → %96. Hesap hatası 0, yanlış çekirdek %0.

| Sıra | Yük | İlk 30 sn | Tüm 60 sn | Boşta üstü | Tekrar farkı | Son 30 sn frekans (A510/A715/prime) | En sıcak A715 |
|---|---|---|---|---|---|---|---|
| 1 | **C8 fp32_l2** (FP32 FMA, 256 KiB tampondan akış) | **6,39 W** | 6,31 W | 5,68 W | ±0,17 | 1472/2165/2270 MHz (kısıldı) | 85,8 °C |
| 2 | C5 i8_mmla | 5,27 W | 5,41 W | 4,75 W | ±0,05 | tavan | 78,8 °C |
| 3 | C3 fp64_gemm | 5,08 W | 5,23 W | 4,43 W | ±0,09 | tavan | 77,6 °C |
| 4 | C7 mixed | 4,95 W | 5,11 W | 4,46 W | ±0,14 | tavan | 76,2 °C |
| 5 | C4 bf16_mmla | 4,80 W | 4,89 W | 4,21 W | ±0,01 | tavan | 76,2 °C |
| 6 | C9 fp32_dram | 4,76 W | 4,90 W | 4,16 W | ±0,08 | tavan | 65,5 °C |
| 7 | C2 fp32_gemm | 4,54 W | 4,63 W | 3,93 W | ±0,01 | tavan | 71,2 °C |
| 8 | C10 memcopy | 4,13 W | 4,24 W | 3,53 W | ±0,08 | tavan | 60,7 °C |
| 9 | C6 i8_dot | 3,95 W | 4,05 W | 3,38 W | ±0,02 | tavan | 68,6 °C |
| 10 | C1 fp32_reg | 3,93 W | 3,98 W | 3,37 W | ±0,14 | tavan | 64,9 °C |
| 11 | **K0 kuru %100** | **2,46 W** | 2,39 W | 1,59 W | ±0,32 | tavan | 53,5 °C |

**Okuma:**
- **Prime95 / Cinebench farkı ölçüldü:** aynı "%100" altında kuru döngü 2,46 W, kazanan 6,39 W: **2,6 kat** (boşta üstü 3,6 kat).
- Kazanan C8, FMA birimleriyle birlikte L1'i aşan veriyi (L2) taşıyan yük. En yakın rakibinden 1,1 W önde, 60 sn içinde kısılmaya
  giren tek yük. Tavanı ısı belirledi: A715 85,8 °C.
- Yalnız yazmaçta çalışan FMA (C1) FMA birimlerini doldurduğu hâlde 3,93 W'ta kaldı. Watt'ı veri hareketi getiriyor.
- İş hızları kâğıt üstü tepeye yakın: A715'te FP32 %96, FP64 2×128 bit ile tam doluluk, INT8 SMMLA çekirdek başına ~122 işlem/döngü (~%95).
  A510'da BFMMLA yavaş (4 çekirdek 42 GFLOPS, INT8 SMMLA'nın 1/8'i).

**Sonraki CPU turu:** C8'in tampon boyu (128 KiB · 512 KiB · 1 MiB · 2 MiB) ve küme başına karışımlar (ör. A510'da i8_mmla, A715'te C8).

## Faz 2 · GPU motoru, ilk cihaz ölçümleri (güçsüz)

`GpuEngineDeviceTest`, 2026-10-01 01:51, kablo takılı, ImageReader yüzeyi 632×1368, sahne %50 (316×684), hedef kare 40 ms.
Kare zamanı iki zaman damgasıyla bölündü: yakıcı + görünen geçiş (sahne).

| Yakıcı | Kare | Yakıcı | Sahne | Gönderim/kare | Gönderim başı | Hız |
|---|---|---|---|---|---|---|
| G1 FP32 | 39,3 ms | 15,7 ms | 23,7 ms | 15 | 1,05 ms | ~1,0 TFLOPS (yakıcı süresine göre) |
| G2 FP16 | 38,5 ms | 15,0 ms | 23,6 ms | 3 | ~5 ms | ~0,2 TFLOPS: FP32'den ~5 kat yavaş (beklenmedik) |
| G3 doku | 45,8 ms | 17,2 ms | 23,7 ms | 2 | ~8,6 ms | her örnek farklı önbellek satırı: belleğe bağlı |
| G4 bant genişliği | 42,1 ms | 16,3 ms | 23,8 ms | 4 | ~4 ms | ~16 GB/s okuma+yazma |
| G5 harmanlama | 40,6 ms | 15,2 ms | 23,7 ms | 69 | 0,22 ms | ~15,7 Gpiksel/s |

- Hesap hatası 0, doğrulanan her gönderim ilk gönderimle birebir aynı.
- **Sahne pahalı:** 316×684'te 23,7 ms. Telefonun gerçek ekranında %50 ölçekte (632×1368) GPU modunda **14 fps** görüldü, GPU %100 meşgul.
  Hangi ölçeğin ve hangi yakıcının en çok watt çektiği gece oturumunda ölçülüyor.
- İki hata bulundu ve düzeltildi: G3 tek gönderimi ~100 ms'ydi (iterasyon 64 → 8); ayar döngüsü kare başına tek gönderimde
  takılıyordu (tek zaman damgasıyla sabit sahne maliyeti ayrılamıyordu → üç damga, `(hedef − sahne) / gönderim başı`).

## Faz 1-2 · Aktif soğutmalı aday oturumu (pilde)

Oturum `20261001-022223`: 20 yük × 2 tekrar, karışık sıra, her koşu 10 sn boşta + 60 sn yük, başlangıçta CPU ≤ 42 °C,
performans modu açık. **Aktif soğutma:** telefon metal yüzeyde, önünde vantilatör (Özcan kurdu). Önceki tablolarla
mutlak watt karşılaştırması bu yüzden kontrollü değil; sıralama kendi içinde geçerli. 40 koşu, hesap hatası 0, şarjda koşu 0,
sıcak başlangıç 0, yanlış çekirdek %0 (ilk koşu dışında: %27,4). GPU yükleri `@preview`: görünen geçiş sahne değil, 0,5 ms'lik önizleme.
**Sahne bu oturumda eski reaktör sahnesiydi** (yeni havuz sahnesi oturum başladıktan sonra yazıldı).

| Sıra | Yük | İlk 30 sn | Tekrar farkı | Son 30 sn | Son 30 sn frekans (A510/A715/prime) | En sıcak A715 |
|---|---|---|---|---|---|---|
| 1 | **fp32_l2 + gpu_fp32** | **10,09 W** | ±0,42 | 9,33 W | 1092/1846/2129 | 85,6 °C |
| 2 | fp32_l2 + sahne %35 (yakıcısız) | 8,52 W | ±0,23 | 8,23 W | 1391/2059/2216 | 85,6 °C |
| 3 | fp32_l2 + gpu_blend | 8,06 W | ±0,18 | 7,79 W | 1275/2077/2331 | 85,6 °C |
| 4 | **fp32_l2** (C8, 256 KiB) | **7,10 W** | ±0,55 | 7,18 W | 1690/2297/2609 | 85,2 °C |
| 5 | A510 fp64_gemm, A715 fp32_l2 | 6,99 W | ±0,19 | 6,98 W | 1768/2288/2608 | 85,0 °C |
| 6 | A510 i8_mmla, A715 fp32_l2 | 6,89 W | ±0,27 | 7,15 W | 1799/2327/2616 | 84,8 °C |
| 7 | fp32_s512k | 6,82 W | ±0,32 | 7,09 W | tavan | 81,4 °C |
| 8 | fp32_s128k | 6,62 W | ±0,11 | 7,06 W | tavan | 84,6 °C |
| 9 | A510 fp32_l2, A715 i8_mmla | 6,21 W | ±0,07 | 6,58 W | tavan | 81,4 °C |
| 10 | **gpu_fp32** (yalnız GPU) | **5,57 W** | ±0,00 | 5,93 W | | 57,3 °C |
| 11 | fp32_s2m | 4,88 W | ±0,09 | 5,00 W | tavan | 61,7 °C |
| 12 | fp32_s1m | 4,81 W | ±0,12 | 5,06 W | tavan | 63,3 °C |
| 13 | gpu_fp16 | 3,70 W | ±0,10 | 3,90 W | | 49,5 °C |
| 14 | gpu_blend | 3,09 W | ±1,02 | 2,73 W | | 52,4 °C |
| 15 | sahne %100 | 3,05 W | ±0,01 | 3,11 W | | 45,1 °C |
| 16 | gpu_texture | 3,03 W | ±0,02 | 3,53 W | | 48,7 °C |
| 17 | sahne %50 | 2,86 W | ±0,08 | 2,98 W | | 45,9 °C |
| 18 | sahne %35 | 2,82 W | ±0,09 | 2,91 W | | 44,5 °C |
| 19 | sahne %25 | 2,79 W | ±0,01 | 2,93 W | | 45,4 °C |
| 20 | gpu_bandwidth | 2,73 W | ±0,06 | 2,99 W | | 48,4 °C |

**Okuma:**
- **Telefonun ölçülen en yüksek gücü: CPU fp32_l2 + GPU fp32 yakıcısı, 10,09 W** (tek koşuda 10,30 W). GPU, CPU'nun 7,10 W'ına
  ~3 W ekliyor; ortak bütçe yüzünden CPU frekansları düşüyor (1092/1846/2129 MHz), toplam yine de en yüksek.
- **Sahne ısı kaynağı değil:** eski reaktör sahnesi ölçekten bağımsız 2,8-3,05 W çekiyor, FP32 yakıcısı 5,57 W. CPU ile birlikte
  sahne 8,52 W, yakıcı 10,09 W. Yakıcı kalmalı; sahne onu yerinden etmemeli (PLAN §13/6, sahneli ≥ yakıcılı × 0,97).
- Eski sahnenin kare süresi: %25 23 ms (43 fps) · %35 40,6 ms · %50 74 ms · %100 246 ms (4 fps). **%100 çözünürlük watt
  getirmiyor** (3,05 W, %25'ten 0,26 W fazla), fps'i 10 kata yakın düşürüyor.
- **C8 tampon boyu:** 128 KiB 6,62 · **256 KiB 7,10** · 512 KiB 6,82 · 1 MiB 4,81 · 2 MiB 4,88 W. L2'ye sığan tampon kazanıyor;
  L2'yi aşınca çekirdekler belleği bekliyor, ~2,2 W düşüyor.
- **Küme karışımları** saf fp32_l2'yi geçemedi (en iyisi A510'da fp64_gemm: 6,99 W).
- GPU yakıcıları tek başına: fp32 5,57 > fp16 3,70 > blend 3,09 (tekrarlar arası ±1,02, gürültülü) > doku 3,03 > bant genişliği 2,73 W.
- Faz 1'de (vantilatörsüz) fp32_l2 6,39 W idi, burada 7,10 W. Soğutma sürdürülen gücü artırıyor olabilir, ama oturumlar
  farklı ve tekrar farkı ±0,55; kontrollü bir karşılaştırma değil.
- Sayaç/akım oranı koşudan koşuya 0,47-1,71 arasında dağılıyor: şarj sayacı ~30 sn'de bir güncellendiği için 60 sn'lik
  koşuda çapraz kontrol işe yaramıyor. 10 dk'lık kontrol hâlâ açık.

**Tarifler buna göre** (`run/StressMode.kt`): Tam yük `fp32_l2+gpu_fp32`, CPU `fp32_l2`, GPU `gpu_fp32`.
Tarifler sahneyi gösteriyor. Sahneli hâlleri pilde henüz ölçülmedi, bu ölçüm sıradaki oturumda.

## Sinematik sahne, telefonda ilk koşu (kablo takılı, güç geçersiz)

2026-10-01 03:29, yeni boru hattı: havuz sahnesi, su simülasyonu, parçacıklar, TAA, bloom, alan derinliği.

- **Cihaz testleri 12/12.** İlk koşuda iki GPU testi `SetupFailed` verdi: Adreno 720 sürücüsü, parçacık compute
  gölgelendiricisini `vkCreateComputePipelines` → `VK_ERROR_UNKNOWN` ile reddetti. Gövde ikiye bölünerek arandı. Sebebi,
  SSBO'dan bütün yapıyı kopyalamak (`Particle p = particles[i]`): SPIR-V 1.4+ bunu `OpCopyLogical`'a derliyor. Alan alan
  kopyalayınca (`loadParticle`) düzeldi. Vertex gölgelendiricisindeki tek `OpCopyLogical` yüklemesini sürücü kabul ediyordu,
  yine de o da alan alana çevrildi.
- **%45 ölçekte (569×1231) kare 49,0 ms, 20,4 fps**, yakıcısız. Masaüstü tahmini (GL zamanlayıcısı × eski sahnenin telefon/masaüstü
  oranı) 35-65 ms idi.

## Sinematik sahnenin gücü (pilde, aktif soğutma)

Oturum `20261001-033735`: 6 yük × 2 tekrar, karışık sıra, 10 sn boşta + 60 sn yük, aynı vantilatör + metal yüzey.
Havuz sahnesi %45 ölçekte (yeni boru hattı, parçacık düzeltmesinden sonra, güverte sekmesi kaldırılmadan önce). Hata 0.

| Yük | İlk 30 sn | Tekrar farkı | Kare | Not |
|---|---|---|---|---|
| fp32_l2 + gpu_fp32@preview | **10,13 W** | ±0,49 | 40,0 ms | en yüksek, görünen geçiş 0,5 ms önizleme |
| fp32_l2 + gpu_fp32 (sahneli, Tam yük tarifi) | 8,97 W | ±0,24 | 52,3 ms (sahne 48,8) | yakıcı kare başına en az 1 gönderim |
| fp32_l2 + sahne | 8,89 W | ±0,40 | 47,8 ms | |
| gpu_fp32@preview | **5,45 W** | ±0,01 | 40,2 ms | |
| gpu_fp32 (sahneli, GPU tarifi) | 3,27 W | ±0,03 | 52,3 ms | |
| sahne | 2,95 W | ±0,01 | 47,8 ms (20,9 fps) | |

**Okuma:**
- **Sahne GPU'yu %100 meşgul ediyor ama 2,95 W çekiyor, FP32 yakıcısı 5,45 W.** GPU'da da Prime95 / Cinebench farkı var:
  ışın yürütme (dallanan döngüler, gecikme bekleyen zincirler) ALU'ları yakıcı kadar doldurmuyor. Eski reaktör sahnesi de ~2,9 W'tı.
- **Kabul kuralı tutmadı:** sahneli Tam yük 8,97 W, sahnesiz 10,13 W → **0,885** (kural ≥ 0,97). Sahne 48 ms sürünce 40 ms'lik
  hedef karede yakıcıya yer kalmıyor (1 gönderim, ~1 ms).
- Zaman paylaştırma kurtarmaz: kuralın tutması için sahnenin GPU zamanından payı ≤ %6,4 olmalı
  (2,95·f + 5,45·(1−f) ≥ 0,97 · 5,45); 48 ms'lik sahneyle bu ~1,3 fps demek.

## Orman sahnesi, telefonda (kablo takılı, güç geçersiz)

`forest@45` (569×1231 iç çözünürlük), yakıcısız, faz faz kare süresi (lab koşusu, GPU zaman damgaları):

| Faz | İçerik | Kare |
|---|---|---|
| O1 | zemin, 676 ağaç (gövde + 160 kart), sis | 16,4 ms (61 fps, ekran yenilemesine dayalı) |
| O2 | + gölge haritası (bir kez), 8 dokunuşlu PCF, sisteki huzmeler (12 nokta) | 16,9 ms |
| O3 | + sık ladin tacı (444 kart/ağaç), kuru dallar, eğrelti, kütük, ıslak zemin | 33,1 ms (30 fps) |
| O4 | + 8192 yağmur damlası, birikinti halkaları | 31,0 ms |

- 60 Hz ekranda kare süreleri 16,7 ms'nin katlarına oturuyor: 33 ms "16,7 ile 33,3 ms arası" demek. Gerçek GPU süresi
  yakıcılı bir koşuda (yakıcının aldığı pay) ya da 120 Hz'de daha net görülür.
- Havuz sahnesi aynı ölçekte 48 ms; orman daha ucuz ama daha çok bellek trafiği çiziyor (üçgen + derinlik). **Pilde gücü
  henüz ölçülmedi** (sinematik modlar arası ve yakıcıya göre).

## Beyaz dünya, telefonda (kablo takılı, güç geçersiz)

| Ölçek | Kare | Not |
|---|---|---|
| white@45 (ilk hâl) | 41,2 ms | iz 400 m, yansıma 120 m |
| white@60 | 67,6 ms | aynı, ölçek seçimi için |
| **white@45** | **32,9-33,2 ms (30 fps)** | iz 220 m, yansıma 90 m, tolerans uzaklıkla büyür, gölge 20 adım |

- Uygulamanın Çalıştır ekranından **Sinematik · Beyaz** (CPU fp32_l2 tam yükte): 21-31 fps (küp odası en pahalı bölge),
  CPU 165-178 GFLOPS, hesap hatası 0. Uygulamanın kendi akışıyla sinematik modun ilk uçtan uca koşusu.
- %60 ölçek netliği artırırdı ama 15 fps'e düşüyor; %45 kaldı.
- Sinematik modların hiçbirinin pilde gücü henüz ölçülmedi (sıradaki lab oturumu).
