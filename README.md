# DuoPanel Streambox Fix — 1.0.3-streambox

Fork não oficial do [DuoPanel original](https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-), baseado no commit `1b8b897e9114046d5ea19f5ead4f47d02693ced3`, para o Faaftech Streambox Pro conectado à multimídia do Omoda 5 HEV 2026.

[Baixar a última versão](https://github.com/victorhramos-dev/DuoPanel/releases/latest) · [GitHub Actions](https://github.com/victorhramos-dev/DuoPanel/actions)

## Melhorias

- Exige cobertura completa da área útil e estabilidade das janelas, incluindo barras do sistema, atividades embutidas e fragmentos de uma mesma aplicação.
- Restaura diretamente a proporção salva, sem parada forçada em 50/50. A espera fixa foi removida quando não há reenvio do Maps em modo direção.
- Prefere a ação nativa do divisor e só verifica um gesto após sua conclusão. Estados intermediários e encaixes automáticos não substituem a preferência salva.
- Limita capas pelo maior eixo, compartilha pedidos simultâneos e não acumula downloads codificados em RAM. O cache tem limite de 4 MiB e responde à pressão de memória.
- Libera callbacks e referências das telas inativas; reduz atualizações repetidas da barra e pausa GPS/bússola quando ela fica oculta.

Os pontos de encaixe e os tamanhos mínimos continuam sendo controlados pelo Android.

## Instalação

1. Baixe o APK **DuoPanel Streambox Fix** em [Releases](https://github.com/victorhramos-dev/DuoPanel/releases/latest). O pacote `com.abdullaherturk.duopanel.streamboxfix` permanece separado do original, sem importar suas preferências.
2. Desative a acessibilidade e a abertura automática do DuoPanel original para evitar dois serviços controlando o divisor.
3. Permita a sobreposição e habilite **DuoPanel Streambox Fix — split screen** em Acessibilidade. Se o Android bloquear essa permissão, use **Informações do app → menu → Permitir configurações restritas**.
4. Selecione os aplicativos, configure a proporção e teste com o veículo estacionado.

A assinatura pertence ao fork, não ao autor original. A mesma chave é preservada para permitir atualizações das compilações anteriores do Streambox Fix.

## Compilação e release no GitHub

- `android-ci.yml` executa os testes debug/release em pushes na `main` e pull requests, sem precisar de segredos. Publica os relatórios de teste e lint como artefatos.
- `release.yml` é acionado por tags `vX.Y.Z-streambox`. Compila, testa, assina e verifica o APK no GitHub Actions antes de outro job publicar o APK e `SHA256SUMS` na GitHub Release. Nenhum APK local é enviado pelo workflow.
- A versão do manifesto precisa coincidir com a tag. A assinatura APK v2 e a impressão digital do certificado também são verificadas; APKs depuráveis são rejeitados.
- Os workflows usam JDK 17, Android SDK 34, Build Tools 34.0.0 e Gradle 8.7, com actions fixadas por SHA e checksum da distribuição Gradle.

Para a próxima versão, atualize `versionCode` e `versionName` em `app/build.gradle`, envie o commit à `main` e publique uma tag correspondente. Um rerun manual pode usar `gh workflow run release.yml --ref vX.Y.Z-streambox` em uma tag existente, antes de haver release publicada para ela.

### Assinatura da CI

Configure estes **GitHub Actions secrets** no repositório; nunca adicione a chave ou senhas ao Git:

- `ANDROID_KEYSTORE_BASE64`: keystore codificado em Base64.
- `ANDROID_STORE_PASSWORD`: senha do keystore.
- `ANDROID_KEY_ALIAS`: alias da chave.
- `ANDROID_KEY_PASSWORD`: senha da chave.

O workflow restaura a chave apenas no diretório temporário do runner e a remove ao terminar. As credenciais são fornecidas ao Gradle por variáveis de ambiente, não por arquivos versionados. O job de compilação tem somente leitura do repositório; somente o job de publicação recebe permissão para criar a release, sem acesso ao material de assinatura do outro runner.

Para desenvolvimento, `keystore.properties` local continua suportado conforme `keystore.properties.example`; `ANDROID_KEYSTORE_PATH` e as variáveis de assinatura acima têm precedência. Os testes podem ser executados com `./gradlew test`, sem chave de release.

## Verificação da versão 1.0.3

- 78 casos em debug e 78 em release, incluindo cobertura de APIs 28, 30 e 33: geometria, abertura do par, callbacks antigos, decodificação, cancelamento, reciclagem de linhas e ciclo de vida.
- No emulador Android 13/API 33, um PNG 8192×512 limitado ao maior eixo de 512 pixels passou de 16 MiB para 64 KiB de bitmap decodificado. Isso não é uma medição da RAM total do aplicativo no Streambox.
- APK assinado e painel musical em split com Settings verificados no emulador 1920×720. Capas HARDWARE também foram verificadas no APK real.
- A redução total de RAM e as novas otimizações não foram medidas fisicamente no Streambox/Omoda.
- `lintRelease` ainda apresenta erros preexistentes (10 erros e 69 avisos na verificação local). A CI mantém esse passo explicitamente não bloqueante e publica o relatório, sem novas supressões; falhas de testes ou de compilação impedem a release.

---

<div align="center">
  <a href="https://buymeacoffee.com/abdullaherturk" target="_blank">
    <img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" style="height: 60px !important; width: 217px !important;">
  </a>
</div>

<h1 align="center">DuoPanel (for Android Head Unit)</h1>

<p align="center">
  <b>Araç ekranları için iki uygulamayı yan yana açan bölünmüş ekran başlatıcısı</b><br>
  <b>A split-screen launcher that opens two apps side by side on car head units</b>
</p>

<p align="center">
  <img src="https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-/blob/main/images/duopanel-split.jpg?raw=true" alt="DuoPanel - Haritalar ve müzik yan yana / Maps and music side by side" width="100%">
</p>

<p align="center">
  <img alt="Android" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Split screen" src="https://img.shields.io/badge/Split%20screen-Android%2012L%2B-1f6f8b">
  <img alt="Languages" src="https://img.shields.io/badge/Dil%20%2F%20Language-TR%20%7C%20EN-blue">
  <img alt="Version" src="https://img.shields.io/badge/version-1.0.3--streambox-informational">
</p>

---

<details>
<summary><h2>Türkçe</h2></summary>

### Nedir?

DuoPanel, seçtiğiniz iki uygulamayı tek dokunuşla Android'in bölünmüş ekranında yan yana açar. Örneğin solda Google Haritalar, sağda müzik uygulaması. Uygulama özellikle **Carlinkit Tbox Ambient** gibi geniş ekranlı Android araç kutuları ve multimedya ekranları için yazıldı. Tablet ve telefonlarda da çalışır.

### Neden yapıldı?

Araç ekranlarında iki uygulamayı yan yana açmak normalde birkaç adım sürer: son uygulamalar ekranı, uygulama menüsü, "bölünmüş ekran", ikinci uygulamayı seçme... Sürüş sırasında bu hem zor hem tehlikeli. Her açılışta oran da varsayılana döner. DuoPanel bu işi tek dokunuşa indirir:

- Kayıtlı ikiliyi doğru tarafta ve sizin belirlediğiniz oranda açar.
- Cihaz açılınca son kullanılan ikiliyi kendiliğinden başlatabilir.
- Ekranın üstünde saat, hava durumu, hız ve yön gösteren şık bir bilgi şeridi sunar.
- Müzik uygulamasının yerine, arka planda çalan şarkıyı gösteren **Müzik paneli** açabilir.

<p align="center">
  <img src="https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-/blob/main/images/duopanel-settings.jpg?raw=true" alt="DuoPanel ana ekran ve ayarlar" width="80%">
</p>

### Özellikler

**Bölünmüş ekran**
- Sol ve sağ taraf için herhangi bir uygulama seçimi. Her tarafın geçmişi tutulur, son kullanılanlar tek dokunuşla seçilir.
- **Sol taraf genişliği:** %30, %50 veya %70. Ayracı elle çektiğinizde yeni oran o ikili için hatırlanır.
- **Yer değiştirme:** İki uygulamanın yeri tek dokunuşla değişir. Cihaz destekliyorsa sistemin kendi yöntemi kullanılır (ayraç menüsü ya da ayraca çift dokunma).
- **Kayıtlı ikililer:** Sık kullandığınız ikilileri kaydedin, listeden açın ya da 🏠 ile ana ekrana kısayol olarak ekleyin.
- **Açılışta başlat:** Cihaz açılınca DuoPanel çalışır ve istenirse son ikiliyi kendiliğinden açar.

**Bilgi şeridi**
- Saat ve tarih, hava durumu ([Open-Meteo](https://open-meteo.com/), anahtar gerekmez), anlık hız (km/sa) ve gidiş yönü (pusula).
- Tüm uygulamalar menüsü, oran düğmesi ve yer değiştirme düğmesi.
- Şeride uzun basınca **Ortala · Ayarlar · Gizle** menüsü açılır.
- Hava durumu konumu GPS ile otomatik bulunur ya da şehir/ilçe adıyla elle seçilir.
- Şerit gizliyse yer değiştirme düğmesi ekranda ayrı bir düğme olarak görünür. Sürükleyip istediğiniz yere taşıyabilirsiniz.
- İsteğe bağlı **yüzen simge:** İkili kapandığında tek dokunuşla geri açar.

**Müzik paneli**
- Sol ya da sağ taraf için bir uygulama yerine "Müzik paneli" seçilebilir.
- Seçtiğiniz müzik uygulamasında çalan şarkıyı, kapağı ve kontrolleri gösterir. Desteklenen uygulamalarda karıştırma ve tekrar da vardır.
- Kapağın renginden otomatik vurgu rengi, açık ve koyu tema.
- **Sıradakiler** listesi ve uygulamanın paylaştığı **kütüphane:** sekmeler, klasörler, çalma listeleri. Klasörlere uzun basarak sekme olarak sabitleyebilirsiniz.
- Çalan şarkı listede vurgulanır, liste kendiliğinden ona kayar.
- Kapak hareketleri: sola/sağa kaydırınca sonraki/önceki şarkı, tek dokunuş çalan şarkıya gider, uzun basış müzik uygulamasını açar.
- Müzik uygulaması kapalıysa play tuşu onu arka planda başlatmayı dener.
- Panel ayrı bir süreçte çalışır. Bir sorun olursa ikiliyi ya da DuoPanel'i etkilemez.

> **Not:** Panelin ne kadarını gösterebileceği müzik uygulamasının dışarıyla paylaştığı bilgilere bağlıdır. Çalan şarkı ve kontroller her uygulamada çalışır. Sıradakiler listesi ve kütüphane ise yalnızca bunları paylaşan uygulamalarda görünür (örn. SimpMusic). Paylaşmayanlarda (örn. RVX Music) bu bölümler boş kalır.

**Google Haritalar**
- Haritalar'ı doğrudan hedefsiz **sürüş modunda** açma seçeneği.
- Navigasyon bitince, ana harita göründükten 3 sn sonra Haritalar'ı tekrar sürüş moduna döndürme seçeneği.

**Diğer**
- Açık, koyu ve otomatik tema (sağ üstteki ☀ düğmesi).
- Sorun giderme için uygulama içi kayıt (**Kaydı göster**).

### Gereksinimler

| | |
|---|---|
| Android sürümü | En az Android 7.0. Tek dokunuşla bölünmüş ekran için **Android 12L (API 32) ve üzeri** önerilir. |
| Ekran | Bölünmüş ekranı destekleyen geniş ekranlı cihazlar (araç kutusu, multimedya ekranı, tablet) |
| Test edilen cihazlar | Orijinal sürüm: Carlinkit Tbox Ambient, Samsung Galaxy Tab S8 Ultra. Bu yerel sürüm: Android 13 emülatörü, 1920×720; fiziksel araç cihazında doğrulanmadı. |

### Kurulum

1. Bu kodla birlikte verilen **DuoPanel Streambox Fix** APK dosyasını kurun; yukarıdaki yerel sürüm kurulum notlarını izleyin.
2. DuoPanel'i açın ve **Kurulum** düğmesine dokunun. Her adımın düğmesi doğrudan ilgili ayar ekranını açar.

| İzin | Ne için |
|---|---|
| Diğer uygulamaların üzerinde göster | Bilgi şeridi, yüzen simge ve yer değiştirme düğmesi |
| Erişilebilirlik hizmeti (**DuoPanel Streambox Fix — bölünmüş ekran**) | Bölünmüş ekranı başlatmak, ayracı ayarlamak, ekrandaki pencereleri algılamak |
| Kısıtlanmış ayarlara izin ver | Yalnızca Android erişilebilirlik hizmetini "kısıtlanmış ayar" diye engellerse: Uygulama bilgisi → sağ üstteki ⋮ → *Kısıtlanmış ayarlara izin ver* |
| Konum (her zaman izin ver) | Hız, pusula ve hava durumu |
| Pil kısıtlaması yok | Şeridin ve açılışta başlatmanın arka planda kapanmaması |
| Bildirim erişimi | Yalnızca Müzik paneli için. Android çalan şarkıyı ve kontrolleri yalnızca bu izne sahip uygulamalarla paylaşır. Bildirimler okunmaz. |

### İsteğe bağlı: ADB izni

Bazı cihazlar (özellikle araç kutuları) yeniden başlatıldıktan sonra erişilebilirlik hizmetini kapatır. Aşağıdaki komutu bilgisayardan bir kez çalıştırırsanız, DuoPanel hizmet kapandığında onu kendisi yeniden açar:

```bash
adb shell pm grant com.abdullaherturk.duopanel.streamboxfix android.permission.WRITE_SECURE_SETTINGS
```

Zorunlu değildir. Bu izin olmadan da her şey çalışır, yalnızca hizmet kapanırsa elle açmanız gerekir.

### Kullanım

1. **Sol** ve **Sağ** kartlarda **Değiştir**'e dokunup birer uygulama seçin. Müzik paneli de listededir.
2. **Sol taraf genişliği** için %30, %50 veya %70 seçin.
3. **İkiliyi aç**'a dokunun. İkili açıldıktan birkaç saniye sonra ayraç seçtiğiniz orana kendiliğinden kayar.
4. Sık kullandığınız ikili için **Bu ikiliyi kaydet**'e dokunun. Kayıtlı ikiliyi 🏠 ile ana ekrana kısayol olarak ekleyebilirsiniz.
5. Araçta her açılışta aynı ikiliyi istiyorsanız **Açılışta başlat** ve **Açılışta son kullanılan ikiliyi aç** seçeneklerini açın.

### Dil desteği

- Türkçe
- English

Uygulama cihazın sistem diline göre otomatik olarak Türkçe ya da İngilizce açılır. Diğer dillerde İngilizce kullanılır.

### Bilinen sınırlamalar

- Bölünmüş ekranın davranışı üreticiye göre değişir. Bazı araç kutularında ayracın hazır oran komutları yoktur. DuoPanel o zaman ayracı sürükleyerek ayarlar, yavaş cihazlarda bu birkaç saniye sürebilir.
- Bazı uygulamalar bölünmüş ekranda kendi kendine tam ekrana geçebilir ya da en küçük pencere genişliği nedeniyle belirli oranları kabul etmeyebilir.
- Müzik panelinde sıradakiler listesi, kütüphane ve tekrar düğmesi müzik uygulamasının desteğine bağlıdır.

### Gizlilik

- Hesap, reklam ya da analiz yoktur.
- İnternet yalnızca hava durumu için kullanılır: [Open-Meteo](https://open-meteo.com/) ve konum kapalıyken yaklaşık konum için [ipapi.co](https://ipapi.co/).
- Konum cihazdan dışarı gönderilmez, yalnızca hava durumu sorgusunda koordinat olarak kullanılır.
- Erişilebilirlik hizmeti yalnızca pencere konumlarını ve bölünmüş ekran kontrollerini okur. Yazdıklarınızı kaydetmez.

### Destek

Hata bildirimi ve öneriler için [Issues](https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-/issues) bölümünü kullanabilirsiniz. Bildirime **Kaydı göster** ekranındaki kaydı da eklerseniz sorun çok daha hızlı bulunur.

Uygulamayı beğendiyseniz: [☕ Buy me a coffee](https://buymeacoffee.com/abdullaherturk)

</details>

---

<details>
<summary><h2>English</h2></summary>

### What is it?

DuoPanel opens two apps of your choice side by side in Android's split screen with a single tap, for example Google Maps on the left and a music app on the right. It was written especially for wide-screen Android car boxes and head units such as the **Carlinkit Tbox Ambient**. It also works on tablets and phones.

### Why?

On a car screen, opening two apps side by side normally takes several steps: Recents, the app menu, "split screen", picking the second app... While driving this is both awkward and unsafe, and the split ratio goes back to the default every time. DuoPanel turns it into one tap:

- Opens your saved pair on the right sides and at the ratio you chose.
- Can start the last used pair automatically when the device boots.
- Shows a clean info strip with time, weather, speed and heading on top of the screen.
- Can show a **Music panel** with the song playing in the background, in place of the music app itself.

<p align="center">
  <img src="https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-/blob/main/images/duopanel-settings.jpg?raw=true" alt="DuoPanel main screen and settings" width="80%">
</p>

### Features

**Split screen**
- Pick any app for the left and right side. Each side keeps a history so recent apps are one tap away.
- **Left side width:** 30%, 50% or 70%. If you drag the divider by hand, the new ratio is remembered for that pair.
- **Swap sides:** swaps the two apps with one tap. Uses the system's own method where available (divider menu or double-tap on the divider).
- **Saved pairs:** save the pairs you use often, open them from the list or add them to the home screen as a shortcut with 🏠.
- **Start at boot:** DuoPanel starts with the device and can open the last used pair automatically.

**Info strip**
- Time and date, weather ([Open-Meteo](https://open-meteo.com/), no API key needed), current speed (km/h) and heading (compass).
- All apps menu, ratio button and swap button.
- Long-press the strip for the **Center · Settings · Hide** menu.
- The weather location is found by GPS or set by hand by city/district name.
- When the strip is hidden, the swap button appears on screen on its own. Drag it wherever you like.
- Optional **floating bubble** that reopens the pair with one tap after it has been closed.

**Music panel**
- Pick "Music panel" instead of an app for the left or right side.
- Shows the song playing in your chosen music app with its cover and controls. Shuffle and repeat are available where the app supports them.
- Accent color taken from the cover, light and dark themes.
- **Up next** queue and the **library** shared by the app: tabs, folders, playlists. Long-press a folder to pin it as a tab.
- The playing song is highlighted and the list scrolls to it automatically.
- Cover gestures: swipe left/right for next/previous, tap to jump to the playing song, long-press to open the music app.
- If the music app is closed, the play button tries to start it in the background.
- The panel runs in its own process, so a problem there cannot affect the pair or DuoPanel itself.

> **Note:** How much the panel can show depends on what the music app shares. The playing song and the controls work with every app. The queue and the library appear only in apps that share them (e.g. SimpMusic). Apps that don't (e.g. RVX Music) leave those sections empty.

**Google Maps**
- Option to open Google Maps directly in destination-free **driving mode**.
- Option to return Google Maps to driving mode after navigation ends, 3 s after the main map appears.

**Other**
- Light, dark and automatic theme (the ☀ button at the top right).
- In-app log for troubleshooting (**Show log**).

### Requirements

| | |
|---|---|
| Android version | Android 7.0 or later. **Android 12L (API 32) or later** is recommended for one-tap split screen. |
| Screen | Wide-screen devices with split-screen support (car box, head unit, tablet) |
| Tested on | Original upstream: Carlinkit Tbox Ambient and Samsung Galaxy Tab S8 Ultra. This local build: Android 13 emulator, 1920×720; not verified on physical automotive hardware. |

### Installation

1. Install the **DuoPanel Streambox Fix** APK supplied with this source, following the local-build notes above.
2. Open DuoPanel and tap **Setup**. Each step's button opens the exact settings screen.

| Permission | What for |
|---|---|
| Display over other apps | Info strip, floating bubble and swap button |
| Accessibility service (**DuoPanel Streambox Fix — split screen**) | Starting split screen, setting the divider, detecting the windows on screen |
| Allow restricted settings | Only if Android blocks the accessibility service as a "restricted setting": App info → ⋮ at the top right → *Allow restricted settings* |
| Location (allow all the time) | Speed, compass and weather |
| Unrestricted battery | Keeps the strip and the boot start alive in the background |
| Notification access | Only for the Music panel. Android shares the playing song and its controls only with apps that have this access. Notifications themselves are not read. |

### Optional: ADB permission

Some devices (car boxes in particular) turn the accessibility service off after a reboot. If you run the command below once from a computer, DuoPanel turns the service back on by itself:

```bash
adb shell pm grant com.abdullaherturk.duopanel.streamboxfix android.permission.WRITE_SECURE_SETTINGS
```

This is optional. Everything works without it; you just have to turn the service back on by hand if it gets switched off.

### Usage

1. Tap **Change** on the **Left** and **Right** cards and pick an app for each. The Music panel is in the list too.
2. Choose 30%, 50% or 70% for **Left side width**.
3. Tap **Open the pair**. A few seconds after the pair opens, the divider moves to your ratio automatically.
4. Tap **Save this pair** for pairs you use often. Add a saved pair to the home screen as a shortcut with 🏠.
5. If you want the same pair every time the car starts, turn on **Start at boot** and **Open the last used pair at boot**.

### Languages

- Türkçe
- English

The app follows the device language and opens in Turkish or English. Any other language falls back to English.

### Known limitations

- Split-screen behaviour differs between manufacturers. Some car boxes have no preset ratio commands on the divider. DuoPanel then sets the ratio by dragging the divider, which can take a few seconds on slower devices.
- Some apps may leave split screen and go full screen on their own, or refuse certain ratios because of their minimum window width.
- The Up next queue, the library and the repeat button in the Music panel depend on the music app's support.

### Privacy

- No account, no ads, no analytics.
- Internet is used only for the weather: [Open-Meteo](https://open-meteo.com/), plus [ipapi.co](https://ipapi.co/) for a rough location when location is off.
- Your location never leaves the device except as coordinates in the weather request.
- The accessibility service only reads window positions and split-screen controls. It does not record what you type.

### Support

Use [Issues](https://github.com/abdullah-erturk/DuoPanel-for-Android-Head-Unity-/issues) for bug reports and suggestions. Including the log from the **Show log** screen makes problems much faster to find.

If you like the app: [☕ Buy me a coffee](https://buymeacoffee.com/abdullaherturk)

</details>

---

<p align="center">
  Bu uygulama <b>Abdullah ERTÜRK</b> tarafından kodlanmıştır.<br>
  This app was coded by <b>Abdullah ERTÜRK</b>.<br><br>
  <a href="https://github.com/abdullah-erturk">github.com/abdullah-erturk</a> ·
  <a href="https://erturk-dev.netlify.app">erturk-dev.netlify.app</a>
</p>
