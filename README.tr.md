<div align="center">

# Stres

**Tek bir telefon için stres testi ve benchmark: telefonun gerçekten çekebildiği en yüksek gücü bulmak, bunu yaparken de göze iyi görünmek için.**

[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.20-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)](https://developer.android.com/compose)
[![C++20](https://img.shields.io/badge/C%2B%2B-20-00599C?logo=cplusplus&logoColor=white)](app/src/main/cpp)
[![AArch64 assembly](https://img.shields.io/badge/AArch64-assembly-555555)](app/src/main/cpp/cpu)
[![Vulkan](https://img.shields.io/badge/Vulkan-1.2-AC162C?logo=vulkan&logoColor=white)](app/src/main/cpp/gpu)
[![Min SDK](https://img.shields.io/badge/minSdk-36-3DDC84?logo=android&logoColor=white)](app/build.gradle.kts)
[![Cihaz](https://img.shields.io/badge/cihaz-Honor%20400%20%C2%B7%20Snapdragon%207%20Gen%203-FF6A21)](#cihaz)
[![Durum](https://img.shields.io/badge/durum-tamamland%C4%B1-success)](#durum)

Tam yükte **10,1 W** · aynı çekirdeklerde "kuru %100"ün **2,6 katı** güç · üç sinematik sahne · bütün analiz telefonda

[Soru](#soru) · [Sonuçlar](#sonuçlar) · [Modlar](#modlar) · [Sahneler](#sinematik-sahneler) · [Güç nasıl ölçülüyor](#güç-nasıl-ölçülüyor) · [Yük nasıl üretiliyor](#yük-nasıl-üretiliyor) · [Mimari](#mimari) · [Derleme](#derleme)

*[English](README.md)*

<br />

<img src="docs/images/cover.jpg" alt="Telefonda her sinematik sahneden bir kare: reaktör havuzu, yağmur ormanı, beyaz dünya" width="760" />

</div>

---

Kişisel ve tek cihazlık bir proje: yalnız bir **Honor 400**'ü hedefler, hiçbir
yerde yayınlanmaz, o telefonda bir APK olarak yaşar. Bu darlık bilerek seçildi.
Aşağıdaki her sayı, kodun yazıldığı cihazın kendisinde ölçüldü; koddaki her
seçim de genel bir telefona göre değil, bu ölçümlere göre yapıldı.

## Soru

Telefondaki stres testlerinin çoğu aynı şeyi gösterir: bütün çekirdekler %100.
Bu sayı bir çekirdeğin ne kadar meşgul olduğunu söyler, ne kadar çalıştığını
değil. Bir çekirdek birbirine bağlı tamsayı toplamalarıyla tamamen doldurulabilir
ve birimlerinin neredeyse hepsi boşta kalır. Aynı çekirdek, işlenenleri
önbellekten akan vektör çarp-topla komutlarıyla da doldurulabilir: aritmetik
birimler, yükleme yolu ve önbellek aynı anda çalışır. İkisi de %100 görünür.
Telefonu ısıtan yalnız biridir.

Bu, Cinebench ile Prime95 arasındaki farktır ve bu proje onu ölçer. Bu telefonda:

| Sekiz çekirdekte çalışan | Görünen yük | Pilden çekilen güç |
| --- | --- | --- |
| Birbirine bağlı tamsayı toplamaları (`dry`, "kuru %100") | %100 | **2,46 W** |
| FP32 çarp-topla, işlenenler L2'den akar (`fp32_l2`) | %100 | **6,39 W** |
| Aynısı, artı GPU'nun FP32 yakıcısı (**Tam yük**) | %100 + GPU %100 | **10,1 W** ¹ |

<sub>İlk iki satır aynı oturumdan, oda sıcaklığında: aynı %100, 2,6 kat güç.
¹ Daha sonraki bir oturumda, telefon metal bir yüzeyde ve vantilatörün önündeyken ölçüldü;
soğutma sürdürülebilen gücü artırır. O oturumda `fp32_l2` tek başına 7,10 W çekti.</sub>

Bu yüzden buradaki tek ölçüt **pilden çekilen güç, watt cinsinden**. Bir yük
ancak ölçülen hiçbir şey ondan fazla çekmiyorsa "en yüksek"tir. Sıcaklık eşiği
de, kendi koruyucu kısması da yok: telefonun kendi denetleyicileri ne yaparsa
onu yapar, sonuç ekranı ne zaman ve ne kadar yaptıklarını gösterir.

Aynı ders sonradan GPU'da da çıktı. Işın yürüten bir sahne GPU'yu %100 meşgul
eder ama 2,95 W çeker; aynı GPU'da FP32 çarp-topla yakıcısı 5,45 W çeker.
Meşgul olmak orada da çalışmak demek değil.

## Sonuçlar

Bütün değerler Android'in `BatteryManager`'ından okunan pil gücüdür; telefon
kabloya bağlı değilken, kendi kendine koşturduğu oturumlarda ölçüldü (bkz.
[Güç nasıl ölçülüyor](#güç-nasıl-ölçülüyor)). Her oturumun koşullarıyla birlikte
tam kayıt [docs/OLCUMLER.md](docs/OLCUMLER.md)'de.

**CPU çekirdek yükleri, sekiz çekirdekte, ilk 30 sn ortalaması, her biri rastgele sırayla iki kez, oda sıcaklığında:**

| Yük | Neyi çalıştırır | Güç |
| --- | --- | --- |
| `fp32_l2` | FP32 FMA, işlenenler 256 KiB'lık tampondan akar | **6,39 W** |
| `i8_mmla` | INT8 matris çarp-topla (SMMLA) | 5,27 W |
| `fp64_gemm` | FP64 FMA, L1'den 8×6 dış çarpım | 5,08 W |
| `mixed` | FMA, tamsayı zinciri, çarpıcı ve yazma aynı anda | 4,95 W |
| `bf16_mmla` | BF16 matris çarp-topla (BFMMLA) | 4,80 W |
| `fp32_dram` | FP32 FMA, işlenenler 32 MiB'tan akar | 4,76 W |
| `fp32_gemm` | FP32 FMA, L1'den 8×12 dış çarpım | 4,54 W |
| `memcopy` | 32 MiB kopyalama: saf bellek bant genişliği | 4,13 W |
| `i8_dot` | INT8 nokta çarpımı (SDOT) | 3,95 W |
| `fp32_reg` | Yalnız yazmaçta FP32 FMA | 3,93 W |
| `dry` | Birbirine bağlı tamsayı toplamaları | **2,46 W** |

İki şey öne çıkıyor. Gücü çeken yalnız aritmetik değil: yalnız yazmaçta çalışan
FMA, FMA birimlerini doldurduğu hâlde 3,93 W'ta kalıyor. Gücü getiren, verinin
taşınması. Tamponun da bir tatlı noktası var: aynı yük 128 KiB'ta 6,62 W,
**256 KiB'ta 7,10 W**, 512 KiB'ta 6,82 W çekiyor; 1 ve 2 MiB'ta ise 4,8 W'a
düşüyor, çünkü çekirdekler çoğunlukla belleği bekliyor. (Bu ikinci sayı dizisi
telefonun metal yüzeyde ve vantilatör önünde olduğu oturumdan; bir oturumun
içinde önemli olan sıralamadır.)

**GPU yakıcıları, tek başına:** FP32 ALU 5,57 W · FP16 ALU 3,70 W · harmanlama
3,09 W · doku örnekleme 3,03 W · bellek bant genişliği 2,73 W.

**Birlikte:** CPU'da `fp32_l2`, GPU'da FP32 yakıcısı ölçülenlerin en yükseği: iki
oturumda **10,09 W ve 10,13 W**. CPU ortak güç bütçesine biraz frekans bırakıyor
(A510 / A715 / prime yaklaşık 1,1 / 1,8 / 2,1 GHz'e oturuyor) ve toplam yine de
en yüksek kalıyor.

## Modlar

| Mod | Yük | Ekranda | Ölçülen |
| --- | --- | --- | --- |
| **Tam yük** | `fp32_l2` + GPU FP32 yakıcısı | yük kadranı (GPU yakıcıda) | **10,1 W** |
| **Sinematik** | `fp32_l2` + bir sahne | ana ekranda seçilen üç sahneden biri | havuzla 8,9 W |
| **CPU** | sekiz çekirdekte `fp32_l2` | kadran | 7,1 W (soğutmasız 6,4 W) |
| **GPU** | GPU FP32 yakıcısı | yük kadranı | 5,5 W |
| **Kuru %100** | `dry` | kadran | 2,5 W (soğutmasız) |

<sub>"Soğutmasız" yazmayanlar telefon metal yüzeyde ve vantilatör önündeyken ölçüldü.</sub>

Bir koşu 5, 15 ya da 30 dakika sürer, ya da durdurulana kadar. Önce 10 saniye
dinlenir ve telefonun hiçbir şey yapmazken çektiği gücü ölçer; ardından ekranı
her seferinde aynı biçimde, tam parlaklık ve en yüksek yenileme hızında sürer ki
koşular karşılaştırılabilsin.

Sahnelerin Tam yükün arka planı değil ayrı bir mod olması ölçümle verilmiş bir
karar: sahne ekrandayken yakıcıya kare süresi kalmıyor ve tam yük 8,97 W'a,
yani en yüksek değerin %88,5'ine iniyor. Ölçümden önce yazılan kural %97 idi; bu
yüzden sahneler kendi modlarına taşındı ([docs/PLAN.md](docs/PLAN.md), §13/7).

## Sinematik sahneler

Projenin yarısında eklenen ikinci hedef: *bu telefonun gösterebileceği en
etkileyici görüntü.* Her sahne ekran çözünürlüğünün %45'inde çizilir ve zamanla
tam çözünürlüğe kurulur: her kare örneklerini piksel altı bir kaymayla oynatır
ve rastgele etkilerini yeniden tohumlar; zamansal geçiş önceki kareleri kamerayla
yeniden hizalar ve yeni kareye göre kırpar; Catmull-Rom süzgeci sonucu ekrana
büyütür. Telefonun GPU'sunda yaklaşık 1 TFLOPS aritmetik ama yalnız yaklaşık
16 GB/s bellek bant genişliği var; bu yüzden her etki belleğe değil aritmetiğe
mal olacak biçimde seçildi.

### Havuz

<img src="docs/images/scene-pool.jpg" alt="Telefonda havuz sahnesi: havuzun üstündeki halka, suyun altındaki yakıt çubukları, halkanın dikenleri yakından, yukarıdan çekirdek" width="100%" />

Havuz güvertesinden görülen bir araştırma reaktörü. Tek bir tam ekran ışın
yürütme gölgelendiricisi salonu, dikenli muhafaza halkasını ve suyun altında
Çerenkov mavisiyle parlayan yakıt demetini çizer. Su yüzeyi, GPU'da saniyede 60
kez dalga denklemiyle çözülen 256 × 256'lık bir yükseklik alanı: çekirdeğin
üstünde patlayan kabarcıklar halka dalgalar başlatır, dalgalar kesişir, havuz
duvarından yansır, kontrol çubuğu tüplerinin çevresinde saçılır; yüzey ışığı
kırıp tabana kostik desenler düşürür. 16.384 GPU parçacığı (yükselen kabarcıklar,
halkanın çevresinde dönen kıvılcımlar), bloom, çekime göre alan derinliği ve bir
darbe: her döngüde bir kez reaktör bir TRIGA gibi parlar. **Kare ~48 ms, ~20 fps.**

### Orman

<img src="docs/images/scene-forest.jpg" alt="Telefonda orman sahnesi: ışığa doğru, yağmurda taçların altından, eğreltiler yakından, açıklıkta yükselirken" width="100%" />

Yağmurdan sonra ılıman bir yağmur ormanı. Havuzun aksine bu sahne geometri:
düzensiz bir ızgarada en çok 676 ladin ve kayın, dalları ve iğne demetleri,
eğreltiler ve devrik kütükler. Hiçbiri bellekte durmaz. Bir köşe gölgelendiricisi
her köşeyi çizimin ve örneğin numarasından kurar (köşe çekme), bir parça
gölgelendiricisi de her kartı bir iğne demetinin ya da yaprak kümesinin taslağına
göre keser. Güneşin gölge haritası bir kez çizilir; bir ışık geçişi her pikselin
ışınını sisin içinde bu haritaya karşı yürütür ve ışık huzmelerini çizer. 8.192
yağmur çizgisi huzmelerin içinde parlar, birikintilerde halkalar yayılır.
**Kare ~31-39 ms, ~25-30 fps.**

### Beyaz

<img src="docs/images/scene-white.jpg" alt="Telefonda beyaz sahne: bir kapıdan geçerken, kaburgalı cam tünel, üst üste küplerden oda, asılı küplerin boşluğu" width="100%" />

Dramdan çok netlik üzerine: sonsuz bir beyaz boşluk, her şeyi yansıtan cilalı
bir zemin ve kesintisiz uçan bir kamera; asılı küplerin boşluğundan, cam bir
koridordan, kaburgalı cam bir tünelden ve duvarları üst üste küplerle örülmüş bir
odadan geçer. Cam ince ve soluk yeşil-mavidir, eğik bakıldıkça koyulaşır; böylece
arkadaki devasa beyaz dünya hep görünür kalır. Alan derinliği, renk saçılması ve
gren yok, bloom neredeyse yok; büyütmenin üstüne hafif bir keskinleştirme var.
**Kare ~38 ms, ~27 fps.**

## Uygulama

<img src="docs/images/app.jpg" alt="Sahne seçicili ana ekran, göstergeleriyle sinematik bir koşu, sonuç ekranı, Tam yükün yük kadranı" width="100%" />

Her şey telefonda analiz edilir, hiçbir şey dışa aktarılmaz. Bir koşunun sonucu
şunları gösterir: tepe ve sürekli güç, ortalama, boştaki güç, harcanan enerji,
pilin başta ve sondaki yüzdesi, bu yükte tam bir pilin ne kadar dayanacağı, hesap
hataları, küme, GPU, bellek ve pil başına en yüksek sıcaklıklar, her kümenin ilk
ne zaman kısıldığı ve CPU'nun iş hızının ne kadar kararlı kaldığı; bir de güç,
sıcaklık, frekans ve işin zaman içindeki grafikleri. Geçmiş ekranı her koşuyu
saklar; tanılama ekranı uygulamanın bu telefonda neleri okuyabildiğini gösterir.

Yukarıdaki ekran görüntüleri şarj kablosu takılıyken alındı; güç alanlarının
*geçersiz* yazması bu yüzden. Şarj olurken pilin akımı telefonun ne çektiği
hakkında bir şey söylemez ve uygulama onu göstermeyi reddeder.

## Güç nasıl ölçülüyor

Telefon, bir uygulamaya pilinin sysfs dosyalarını açmıyor; tek kaynak
`BatteryManager`. Bu telefondaki alanlarına güvenmeden önce onları ölçmek gerekti:

- `CURRENT_NOW` **miliamper** cinsinden (mikroamper değil), boşalırken eksi,
  saniyede bir güncelleniyor. Uygulama birimi ve işareti varsaymıyor, okumalardan
  çıkarıyor.
- `CHARGE_COUNTER` **miliamper-saat** cinsinden (mikroamper-saat değil) ve
  yaklaşık 30 saniyede bir değişiyor; voltaj yalnız pil yayınıyla, yaklaşık aynı
  sıklıkta geliyor. Buradaki bir birim varsayımı bir keresinde pil ömrü tahminini
  bin kat yanlış yapmıştı.
- Kablo takılıyken alınan her örnek geçersiz sayılıyor.

Bir örnekleyici gücü, bütün termal bölgeleri, her kümenin frekansını ve GPU'nun
meşguliyet sayacını saniyede on kez okuyor ve sınırlı bir kayıt tutuyor.

**Yükleri adil karşılaştırmak** kablonun çıkmasını gerektiriyor (şarj olan bir
pil telefonun ne çektiğini söylemez) ve bu telefon kablo çıktığı anda TCP
üzerinden de kablosuz hata ayıklamayla da adb bağlantısını düşürüyor. Bu yüzden
karşılaştırmalar telefonun kendi kendine koşturduğu **lab oturumları** olarak
yapılıyor: `tools/lab.mjs` oturumu kablo üzerinden başlatıyor, telefon kablonun
çıkarılmasını bekliyor, ardından her yük için rastgele sırayla CPU'ların belirli
bir sıcaklığın altına inmesini bekliyor, 10 saniye dinlenirken ve 60 saniye yük
altında ölçüyor ve örnekleri kaydediyor. Kablo geri takılınca `lab.mjs pull` ve
`report` sonuçları çekip sıralıyor.

## Yük nasıl üretiliyor

**CPU.** On bir yük ve kazananın dört tampon boyu türevi; hepsi bir üretici
(`tools/gen_kernels.py`) tarafından AArch64 assembly olarak yazılıyor, böylece
her birinin imzası ve disiplini aynı:

- Her iterasyon grubu sonuçlarının bir **özetiyle** biter ve bu özet altın bir
  değerle karşılaştırılır. Yanlış hesap yaparak telefonu ısıtan bir yük
  yakalanır: hesap hataları sayılır ve her koşuda gösterilir.
- İşçiler kendi çekirdeklerine **sabitlenir**. Qualcomm'un `core_ctl`'ü büyük
  çekirdekleri boştayken, bazen yük altında da park ediyor; bu yüzden sabitleme
  her grupta yeniden denenir ve yanlış çekirdekte koşan gruplar sayılır.
- Bir yük metin olarak yazılır, `0-3:i8_mmla,4-7:fp32_l2+gpu_fp32`; aynı metin
  uygulamanın modlarını, lab oturumlarını ve testleri sürer.

**GPU.** Beş yakıcılı bir Vulkan 1.2 motoru: FP32 ve FP16 aritmetik, doku
örnekleme, bellek bant genişliği ve harmanlama. Aynı anda üç kare uçuşta tutulur;
üç zaman damgası her kareyi yakıcı süresi ve görünen geçiş süresi olarak böler ve
yakıcının gönderim sayısı her karede, yakıcı ile sahne birlikte hedef kare
süresini dolduracak biçimde ayarlanır. Her gönderimin sonuçları ilkine karşı
denetlenir. Bir sürücü davranışı bir gölgelendiriciyi ikiye bölerek bulunmak
zorunda kaldı: Adreno 720 sürücüsü, depolama tamponundan bütün bir yapıyı
kopyalayan compute boru hattını reddediyor (`VK_ERROR_UNKNOWN`);
`particle_params.glsl` bunu açıklıyor ve kurulamayan her boru hattı artık
kayıtta adıyla görünüyor.

## Mimari

```
app/src/main
├── java/dev/ozcan/stress
│   ├── engine/      CPU ve GPU motorları, yük dili, çekirdek ve yakıcı katalogları
│   ├── telemetry/   10 Hz örnekleyici, pil ve termal okuyucular, sysfs düzeni
│   ├── analysis/    akım ve voltajdan güç, pencereli istatistik, iş hızları
│   ├── run/         bir stres koşusu: modlar, denetleyici, analiz, kayıtlar ve depoları
│   ├── lab/         kendi kendine koşan karşılaştırma oturumları: tarif, koşucu, analiz, CSV
│   └── ui/          Compose ekranları: ana, koşu, sonuç, geçmiş, tanılama, lab; grafikler
└── cpp
    ├── cpu/         yükler (üretilmiş AArch64 assembly), tabloları, işçi iş parçacıkları
    ├── gpu/         Vulkan yardımcıları, yakıcı motoru, sinematik sahne çizicisi
    │   └── shaders/ GLSL: yakıcılar, üç sahne, TAA, bloom, alan derinliği, son işlem
    ├── sense/       termal bölgeler, frekanslar ve GPU meşguliyet sayacı için yerel okuyucu
    └── jni_bridge.cpp
tools/
├── gen_kernels.py   yüklerin assembly'sini yazar
├── lab.mjs          lab oturumlarını başlatır, sonuçlarını çeker, sıralar
└── scene_preview.py sahneleri geliştirme makinesinin GPU'sunda çizer
docs/
├── PLAN.md          plan, cihazın ölçülmüş gerçekleri, her karar ve gerekçesi
└── OLCUMLER.md      her ölçüm, koşullarıyla
```

Sahne gölgelendiricileri, telefonun çizicisinin ve bir masaüstü önizlemesinin
paylaştığı düz GLSL. `tools/scene_preview.py` telefonun kendi
gölgelendiricilerini geliştirme makinesinin GPU'sunda OpenGL ile çalıştırıyor:
sahneyi, su simülasyonunu ve parçacıkları, bloom zincirini, alan derinliğini ve
son geçişi, telefonun çözünürlüğünde. Gösterdiği şey, telefonun ekran
görüntüleriyle her karşılaştırıldığında tuttu; böylece bir sahnenin görünüşü
telefon olmadan üzerinde çalışılabildi, telefon ise doğrulamak ve ölçmek için
kullanıldı.

## Okunmaya değer kararlar

Hepsi, karara varan ölçümle birlikte [docs/PLAN.md](docs/PLAN.md)'nin 13.
bölümünde tam olarak yazılı.

| | |
| --- | --- |
| **Tek ölçüt: watt** | "%100" değil, puan değil. Bir yük ancak pilden ölçülen daha fazla güç çekerek kazanır. |
| **Koruma yok** | Sıcaklık eşiği yok, otomatik durdurma yok. Telefonun kendi denetleyicileri devreye girer; sonuç ne zaman girdiğini gösterir. |
| **Analiz telefonda, dışa aktarma yok** | Testi koşturan telefon, sonucunun okunduğu yerdir. |
| **Sahneler ayrı mod** | Sahne, ölçümden önce yazılan %97 kuralına karşı tam yükün %88,5'ini çekti. |
| **Hesap ağır, bellek hafif etkiler** | Ölçülen ~1 TFLOPS'a karşı ~16 GB/s: büyük dokular değil; ışın yürütme, prosedürel geometri, bir kez çizilen gölge haritası. |
| **Yonca değil kadran** | Üç sahneyle uygulama bir reaktör değil bir benchmark gibi okunuyor. |
| **Çip dışı yükler kullanıcıya bırakıldı** | Fener, modem ya da kamera koşu sırasında elle açılabilir; tarifler çipin üstünde kalır. |

## Neler doğrulandı

```bash
./gradlew testDebugUnitTest            # 55 JVM testi
./gradlew connectedDebugAndroidTest    # telefonda 15 test
```

JVM testleri güç aritmetiğini (birim ve işaret çıkarımı, pencereli istatistik),
yük dilini, lab tarifini, örnek kayıtlarını ve biçimlendirmeyi kapsıyor.
Telefondaki testler yalnız telefonun sınayabileceğini sınıyor: her yükün özetinin
koşular ve çekirdek türleri arasında birebir tekrarlandığını ve yapılan işle
değiştiğini, her çekirdeğin sabitlenmiş ve hatasız yandığını, her GPU yakıcısının
hatasız koşup karesini doldurduğunu, **her sahnenin** başlayıp kare ürettiğini,
sensör düzeninin bulunabildiğini ve her modun tarifinin telefonun gerçek yük ve
yakıcı tablolarında geçerli bir yük olduğunu.

Uyarılar her yerde hata sayılıyor: Kotlin, C++ (`-Wall -Wextra -Wshadow
-Wconversion -Werror`) ve gölgelendirici derleyicisi (`glslc -Werror`).

## Derleme

```bash
git clone https://github.com/OzcanOrhanDemirci/stress_test.git
cd stress_test
./gradlew :app:installRelease
```

Gradle için JDK 17 ya da daha yenisi (Android Studio ile gelen yeterli), Android
SDK Platform 36, NDK 29.0.14206865 ve CMake 4.1.2 gerekir; gölgelendiriciler
NDK'nın `glslc`'siyle derlenir. Derleme yalnız `arm64-v8a` içindir ve `minSdk`
36'dır: bu, Android 16 çalıştıran tek bir telefon için yapıldı. Sürüm derlemesi
debug anahtarıyla imzalanır; hiçbir yere dağıtılmaz.

### Cihaz

| | |
| --- | --- |
| Telefon | Honor 400 (DNY-NX9), Android 16 |
| Yonga | Qualcomm Snapdragon 7 Gen 3 (SM7550) |
| CPU | 4 × Cortex-A510 1,8 GHz · 3 × Cortex-A715 2,4 GHz · 1 × Cortex-A715 2,63 GHz |
| GPU | Adreno 720, Vulkan 1.3 |
| Ekran | 1264 × 2736, 60 / 90 / 120 Hz |

### Araçlar

```bash
node tools/lab.mjs start --loads "dry;fp32_l2;fp32_l2+gpu_fp32@preview" --repeat 2   # kablo takılı, sonra çıkar
node tools/lab.mjs pull && node tools/lab.mjs report                               # kablo geri takılınca
python tools/scene_preview.py --scene white --times 3,18,30,44                     # moderngl, pillow, numpy
```

## Teknoloji

| Konu | Seçim |
| --- | --- |
| Uygulama | Kotlin 2.4.20, Material 3 ile Jetpack Compose, coroutines, kotlinx.serialization |
| Yerel kod | NDK ve CMake üzerinden C++20 ve AArch64 assembly, JNI |
| Grafik | Vulkan 1.2, derleme sırasında SPIR-V'ye derlenip gömülen GLSL |
| Derleme | Gradle 9.8.0, Android Gradle Plugin 9.4.1 |
| SDK | derleme, hedef ve en düşük 36 (Android 16) |
| Araçlar | Node.js (lab oturumları), moderngl ile Python (sahne önizlemesi, yük üretici) |

## Durum

**Tamamlandı.** 30 Eylül ile 1 Ekim 2026 arasında yapılmış bir hobi projesi;
burada bir kayıt olarak duruyor. Bakımı yapılmıyor, katkı kabul etmiyor ve
private kalıyor.

## Lisans

Hiçbir lisans verilmez. Bütün hakları saklıdır.

## Yazar

**Özcan Orhan Demirci** · Flutter ve Android geliştirici, İzmir ·
[github.com/OzcanOrhanDemirci](https://github.com/OzcanOrhanDemirci)
