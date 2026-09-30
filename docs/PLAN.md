# stress_test: Plan

> Sürüm 1 · 2026-09-30 · **Özcan onayladı (2026-10-01).** Kararlar §13'te.

## 0 · Tek ölçüt

**Honor 400'de çekilebilen en yüksek güç (watt).** Hedef "kuru %100" değil: Prime95 ile Cinebench'in ikisi de %100 gösterir,
ama Prime95 çok daha fazla ısıtır. Bu uygulama Prime95 tarafında olacak. Başka her şey ikincil.

Özcan'ın kararları (2026-09-30):
- Yalnız **Honor 400**. Başka telefon hedeflenmiyor, o çipe özel ayar serbest.
- **Koruma yok**: sıcaklık eşiği, otomatik durdurma yok.
- Analiz **uygulamanın içinde**. **Dışa aktarma yok.**
- Ekranda **gerçek 3D sahne** (3DMark / FurMark tadında). Isının asıl kaynağı görüntü olmak zorunda değil ama görüntü güç kaybettirmemeli.
- Dil ve araç seçimi serbest.

## 1 · Telefonun ölçülmüş gerçekleri

2026-09-30'da `adb` ile ölçüldü (seri `AKSC025610001388`). Şartname sitelerinden değil, telefondan okundu.

| | |
|---|---|
| Model | DNY-NX9 (Honor 400), Android 16 (API 36), yazılım DNY-N39 10.0.0.208 |
| Çip | **SM7550 = Snapdragon 7 Gen 3**, platform `crow` |
| CPU | cpu0–3: **4× Cortex-A510** ≤1,80 GHz · cpu4–6: **3× Cortex-A715** ≤2,40 GHz · cpu7: **1× Cortex-A715** ≤2,63 GHz · frekans yöneticisi `walt` |
| Komut seti | NEON, fp16, dotprod, **i8mm, bf16** (matris komutları var). SVE çekirdek tarafından bildirilmiyor, kullanılmayacak |
| GPU | **Adreno 720**, **Vulkan 1.3.128**, shader'da fp16 + int8, GPU zaman damgası var (52 ns adım) |
| Ekran | 1264×2736, 60/90/120 Hz (şu an 120), HDR10 / HLG / HDR10+ |
| Bellek | 7,5 GB görünür |
| Zamanlayıcı | önde çalışan uygulama **0–7 (tüm çekirdekler)**, arka plan yalnız 1–3. Test ekranı açık ve önde kalmalı |
| Honor | `ro.product.perfmode=true`: performans modu var. Açık mı kapalı mı Faz 0'da bakılır |

**Kim neyi okuyabiliyor.** `adb shell` kolonu ölçüldü. Uygulama kolonunu Faz 0 ölçecek, çünkü uygulama shell'den daha az yetkili.

| Veri | adb shell | uygulama |
|---|---|---|
| Çekirdek frekansı (`policy0/4/7/scaling_cur_freq`) | ✅ | ? |
| Sıcaklıklar: `cpu-0-*` (A510), `cpu-1-*` (A715), `gpuss-*`, `ddr`, `nspss-*` (NPU), `cpuss-*` | ✅ | ? |
| GPU meşgul süresi (`kgsl-3d0/gpubusy`) | ✅ | ? |
| GPU frekansı | ❌ kapalı | ❌ büyük ihtimalle |
| Pil sysfs (`battery`, `bk_battery`: akım, gerilim, güç) | ❌ Honor kapatmış | yalnız `BatteryManager` API ile |
| Termal HAL (CPU0–7, GPU0–1, nsp, skin, battery) | ✅ `dumpsys thermalservice` | yalnız termal durum + pay (headroom) |

## 2 · En yüksek watt nasıl kurulur

1. **Doymuş bir çekirdekte güç, iş miktarından değil iş türünden gelir.** Hangi birimler aynı anda çalışıyor (FMA, yükleme,
   tamsayı, matris) ve veride ne kadar bit değişiyor. Kuru döngü de %100 gösterir ama birimlerin çoğu boşta durur.
2. **Veri rastgele olacak.** Sıfırla ya da sabitle çarpmak daha az güç çeker. Denormal ve sıfır yok.
3. **Sıcak çip aynı frekansta daha çok çeker** (kaçak akım). Adaylar ancak **aynı başlangıç sıcaklığında** karşılaştırılır.
4. **Sürekli durumda tavanı soğutma belirler.** İyi yükler zamanla aynı watt'a iner, fark frekansta görünür.
   İki sayı raporlanır: **tepe W** (kısılmadan önceki ilk ~30 sn) ve **sürekli W** (son 5 dk).
5. **Kazananı sezgi değil ölçüm seçer.** Her adayın watt'ı telefonda ölçülür, en yüksek olan girer. Prime95'in yıllar içinde
   kendiliğinden vardığı noktaya burada bilinçli olarak ölçüp varılır.

## 3 · Mimari

- **Kotlin + Jetpack Compose:** menü, canlı gösterge (HUD), analiz ekranları, geçmiş.
- **C++ (NDK):** CPU yakıcıları, Vulkan motoru, sysfs okuyucu. Kotlin'le JNI köprüsü.
- **Shader'lar** GLSL ile yazılır, derleme sırasında NDK'nin `glslc`'si SPIR-V'ye çevirir.
- **Neden Vulkan (OpenGL ES değil):** komut tamponları bir kez kaydedilir, her karede yeniden gönderilir. Sürücünün CPU yükü
  neredeyse sıfır olur, bu yüzden CPU tam yükteyken GPU aç kalmaz. GPU süresini de zaman damgasıyla kendimiz ölçeriz.
- **Araç zinciri** (bu makinede `weather_app`'te çalıştığı bilinen kombinasyon): Gradle 9.8.0 · AGP 9.4.1 · Kotlin 2.4.20 ·
  Compose BOM 2026.06.01 · Android Studio JBR 25 · NDK 29.0.14206865 · CMake 4.1.2. Yalnız `arm64-v8a`.
- **Paket adı** `dev.ozcan.stress`. Honor'un oyun merkezinin tanıdığı bir ad değil, dolayısıyla onun kısıcısına takılmaz.

```
stress_test/
  app/src/main/java/…     Kotlin: ekranlar, örnekleyici, geçmiş
  app/src/main/cpp/cpu/   CPU yakıcı çekirdekleri (inline asm)
  app/src/main/cpp/gpu/   Vulkan motoru, sahne, yakıcı geçişleri
  app/src/main/cpp/sense/ sysfs okuyucu
  app/src/main/shaders/   GLSL
  tools/                  geliştirme sırasında ölçüm ve tarama betikleri (adb)
  docs/PLAN.md            bu belge
  docs/OLCUMLER.md        her fazın ölçüm tabloları (tek kaynak)
```

## 4 · CPU yükü

- 8 iş parçacığı, her biri kendi çekirdeğine sabitlenir (`sched_setaffinity`).
- Çekirdekler **inline asm** ile yazılır. Komut karışımı sabit kalır, derleyici yeniden düzenleyemez.
- Adaylar:

| Kod | Ne yapar |
|---|---|
| C1 | FP32 vektör FMA (`fmla`), çok sayıda bağımsız toplayıcı |
| C2 | FP64 vektör FMA |
| C3 | `bfmmla`: bf16 matris çarpımı |
| C4 | `smmla` / `ummla`: int8 matris çarpımı |
| C5 | `sdot`: int8 nokta çarpımı |
| C6 | karışık: FMA + L1'den yükleme + tamsayı ALU, bütün kapılar aynı anda |
| C7 | Prime95 benzeri: L2'de duran veride gerçek FFT kelebekleri |
| C8 | bellek akışı: RAM bant genişliği (Prime95'in "blend"i). Bellek denetleyicisini ve DDR'ı da ısıtır |
| K0 | **Kuru %100**: tamsayı sayaç döngüsü. Cinebench benzeri referans, yalnız karşılaştırma için |

- A510 ve A715 farklı çekirdekleri sevebilir. Seçim **küme başına** yapılır, gerekirse kümelere karışık reçete verilir.
- **Boru hattı doydu mu:** toplayıcı sayısı artırıldığında hız artmıyorsa FMA birimleri doludur. Bunun için kâğıt üstü
  GFLOPS değerine güvenmeye gerek kalmaz, doyma telefonda ölçülür.

## 5 · GPU yükü

- Vulkan ile ekran dışı bir HDR hedefe çizilir. Yolda 3 kare bulunur, böylece GPU kareler arasında boş kalmaz.
- Tek bir gönderim ~50 ms'yi geçmez. Birkaç saniyelik tek iş GPU koruması tarafından sıfırlanır ve uygulamayı çökertir.
- Görünür sahne + **görünmez yakıcı geçişler**. Yakıcı adayları:

| Kod | Ne yapar |
|---|---|
| G1 | FP32 ALU (compute) |
| G2 | FP16 ALU (Adreno'da fp16 genelde iki kat hızlıdır, gücü farklı olabilir) |
| G3 | doku örnekleme ağırlıklı |
| G4 | bant genişliği: büyük tamponu okuma/yazma, DDR'ı ısıtır |
| G5 | ROP / harmanlama: çok katmanlı saydam çizim (FurMark'ın tüyü bunu yapar) |

- **GPU doyduktan sonra fps estetik bir tercihtir.** Watt'ı iş türü belirler, sahnenin çözünürlüğü değil.
- Ölçüt: GPU meşgul **≥ %98** (`gpubusy` uygulamadan okunursa o, okunmazsa zaman damgası) ve adaylar arasında en yüksek W.

## 6 · Sahne (3DMark tadında)

Hazır model yok, tamamen prosedürel: raymarching + son işleme (bloom, hacimsel ışık, sis, ton eşleme). Kod baştan sona bizim.
Lisans derdi yok ve yük ayarlanabilir (adım sayısı, çözünürlük). Üç aday:

- **A · Kanyon uçuşu:** gün batımında kızıl kaya kanyonu, alçaktan uçan kamera, hacimsel bulutlar, nehirde yansıma
  (Wild Life / Steel Nomad havası).
- **B · Reaktör:** karanlık metal salonda dönen, parlayan bir enerji çekirdeği. Etrafında FurMark'ın tüylü halkasının modern
  hâli, ışık huzmeleri, yansımalar.
- **C · Fırtınalı okyanus:** gece, dalgalar, yıldırımla aydınlanan bulutlar, bir deniz feneri.

Faz 3'te iki adayın kaba hâli telefonda gösterilir, seçim Özcan'ın.
**Şart:** sahneli mod, yalnız yakıcının çalıştığı modun watt'ının **≥ %97'sini** vermeli.

## 7 · Tam yük (CPU + GPU)

- CPU yakıcıları + GPU yakıcıları + ölçüm gösterirse bellek akışı.
- **Açlık denemesi:** 8 çekirdeğin hepsi dolu mu, yoksa 7 dolu ve 1 çekirdek sürücüye boş mu? Render iş parçacığının önceliği
  ne olmalı? Hangisi daha yüksek W verirse o seçilir.
- **Ekran:** parlaklık %100 ve 120 Hz sabitlenir. Toplam watt'a katkı yapar, çip ölçümünü bozmaz çünkü yük gücü ayrı gösterilir
  (§8). HDR ile panel parlaklığını artırmak deneysel, ölçülür.

## 8 · Güç ölçümü

- **Birinci yol:** `BatteryManager` anlık akım (`CURRENT_NOW`) × pil gerilimi (pil yayını).
- **İkinci, bağımsız yol:** şarj sayacının (`CHARGE_COUNTER`) eğimi × gerilim. İki yol ~%10 içinde uyuşmalı.
  Uyuşmuyorsa ölçüme güvenilmez, uygulama bunu söyler.
- **Boşta güç:** test başında sahne durdurulmuşken 10 sn ölçülür. **Yük W = toplam W − boşta W** ayrıca gösterilir.
- **Şarj kablosu takılıyken ölçüm geçersizdir.** Uygulama bunu ekranda söyler. Bu bir koruma değil, ölçümün doğruluğu için.
- **Geliştirme sırasında:** kablosuz ADB kullanılır, telefon pilde çalışır ve ben logcat'ten saniyede ~10 örnek okurum.
  Aday taraması betikle yapılır: soğumasını bekle → aynı sıcaklıkta başla → 60 sn koş → tekrarla. Sıra karışık, her aday en az 2 tekrar.
- Android 16'nın CPU/GPU pay (headroom) API'leri ve ADPF performans ipuçları Faz 0'da denenir. Honor desteklemiyorsa bırakılır.

## 9 · Uygulama içi analiz

**Canlı gösterge** (FurMark'ın ekran üstü bilgisi gibi, dokununca gizlenir):
toplam W (anlık + 10 sn ortalama) · yük W · pil % · sıcaklıklar (en sıcak A715, en sıcak A510, GPU, DDR, pil, gövde) ·
küme frekansları (en yüksek frekansa oranla) · GPU meşgul % · CPU skoru (GFLOPS) · GPU skoru · süre · Android termal durumu.

**Sonuç ekranı:** zaman grafikleri (güç, sıcaklık, frekans, performans) ve özet:
tepe W · ortalama W · sürekli W (son 5 dk) · harcanan enerji (Wh / mAh) · pil düşüşü % · bu yükte tahmini pil ömrü ·
en yüksek sıcaklık ve artış · ilk kısılmaya kadar geçen süre · **kararlılık %** (en kötü dakika / en iyi dakika, 3DMark'ın ölçütü).

**Geçmiş:** eski koşuların listesi. İki koşuyu üst üste çizip karşılaştırma. Hepsi telefonda kalır.

## 10 · Modlar

**Tam yük** (varsayılan) · **CPU** · **GPU** · **Kuru %100** (referans: Prime95 / Cinebench farkını kendi gözünle görmek için).
Süre: 5 / 15 / 30 dk / durdurana kadar.

## 11 · Fazlar ve kabul ölçütleri

Her fazın ölçümleri `docs/OLCUMLER.md`'ye yazılır. Bir faz ancak ölçütü ölçülünce kapanır.

| Faz | İş | Kabul |
|---|---|---|
| 0 · Ölçüm aracı | uygulama iskeleti + örnekleyici + kablosuz ADB düzeni | "uygulama neyi okuyabiliyor" tablosu dolu · iki güç yolu ~%10 içinde uyuşuyor · akımın güncellenme hızı biliniyor |
| 1 · CPU | C1–C8 + K0, aday tablosu (W, GFLOPS, frekans, sıcaklık) | kazanan, Kuru %100'den açıkça daha yüksek W · FMA çekirdeği doymuş (toplayıcı eklenince hız artmıyor) |
| 2 · GPU | Vulkan temeli + G1–G5 | GPU meşgul ≥ %98 · aday W tablosu |
| 3 · Sahne | iki kaba aday telefonda → Özcan seçer → cilalama | sahneli W ≥ yakıcı-yalnız W × 0,97 |
| 4 · Tam yük + analiz | açlık denemesi, gösterge, sonuç ekranı, geçmiş | tam yük W ≥ CPU ve GPU modlarının her birinden yüksek |
| 5 · Uzun koşu | 30 dk tam yük | çökme yok · GPU sıfırlanması yok · sonuç ekranındaki sayılar ham kayıtla birebir aynı |
| 6 · Çip dışı yükler | fener, NPU, modem + video kodlayıcı, kamera/ISP, GNSS, Wi-Fi | her yükün tam yüke kattığı W ölçülü · katkısı olmayan girmez |

## 12 · Riskler ve bilinmeyenler

- Sıcaklık dosyaları uygulamaya kapalıysa göstergede yalnız pil sıcaklığı ve termal pay kalır. Root ya da adb olmadan
  başka yol yok. Geliştirmede adb'den okumaya devam ederim.
- GPU frekansı okunamıyor, shell'e bile kapalı.
- Pil akımının çözünürlüğü ve güncellenme hızı henüz bilinmiyor (Faz 0).
- Honor'un performans modu sonucu değiştirir, bu yüzden bütün testler aynı modda yapılır ve mod kayda geçer.
- Düşük pilde çipin pil akımı sınırı (BCL) kısmaya başlar. Başlangıç pil % her koşuda kaydedilir.
- Telefonun kendi termal kapatması yerinde kalır, root olmadan kapatılamaz. Biz ayrıca koruma eklemiyoruz.

## 13 · Kararlar (Özcan, 2026-10-01)

1. **Çip dışı yükler dahil.** Fener LED'i, NPU (Hexagon) ve modem Faz 6'da eklenir. Aynı fazda telefondaki diğer güç
   tüketicileri de denenir: donanım video kodlayıcı, kamera + ISP, GNSS, Wi-Fi. **Kural:** bir yük, tam yüke eklendiğinde
   toplam watt'ı ölçülebilir biçimde artırıyorsa kalır, artırmıyorsa girmez.
   ⚠️ Modemi hücresel veriyle çalıştırmak veri kotasını tüketir. Bu yüzden Faz 6'ya gelince nasıl yapılacağı sorulacak.
2. **GitHub:** private depo `OzcanOrhanDemirci/stress_test`.
3. **Sahne: B · Reaktör.** Modern bir estetik ve radyoaktif his: suyun içinde Çerenkov mavisi parıltı, ışık huzmeleri,
   ısı dalgalanması, FurMark'ın tüylü halkasının modern hâli. Faz 3'te telefonda gösterilir.
4. Tetikleyici **"stress devam"**.
5. **Kod kalitesi baştan yüksek tutulur.** Uygulamanın görevi cihazı zorlamak, bu yüzden kendisi tutarlı ve doğru
   çalışmak zorunda. Hesap yapan her şeyin testi var. Yakıcı çekirdekler Prime95 gibi kendi sonucunu doğrular:
   sabit bir başlangıçtan yapılan hesap, bilinen sonuçla karşılaştırılır. Uyuşmazlık **hesap hatası** olarak sayılıp
   gösterilir. Bu aynı zamanda işin gerçekten yapıldığının kanıtıdır.
6. **Görsellik ikinci hedef: grafik kalitesi odaklı (Özcan, 2026-10-01 02:10).** "Düşük FPS ama süper bir görsellik."
   Sahne telefonda ulaşılabilecek en etkileyici düzeyde olacak: PC oyunlarından alışık olunan ışık, yansıma ve fizik.
   Yol: düşük iç çözünürlük (~%45) + **zamansal büyütme** (alt piksel titreşimi, önceki karelerin yeniden hizalanıp
   biriktirilmesi); hedef ~30 fps. Sahne: **havuz tipi reaktör** (suyun içinde Çerenkov mavisi çekirdek, dalga denklemiyle
   simüle su yüzeyi, kırılma ve kostik, GPU'da parçacık fiziği, hacimsel ışık huzmeleri, iki sekmeli yansıma, bloom,
   alan derinliği, senaryolu kamera). Tasarım kuralı ölçümden: GPU ~1 TFLOPS ama bant genişliği ~16 GB/s, efektler
   hesap ağır / bellek hafif seçilir. **Max watt ilk hedef olarak kalır:** sahneli mod ≥ en iyi yakıcı-yalnız × 0,97;
   karede boşluk kalırsa yakıcı doldurur.
