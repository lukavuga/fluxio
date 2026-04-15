# 🌀 Fluxio

**Fluxio** je sodobna Android aplikacija za upravljanje in nadzor pametnih naprav v lokalnem omrežju in na daljavo.  
Ime izhaja iz latinske besede *fluxus* (pretok), kar simbolizira nemoten pretok podatkov in energije v pametnem domu.

---

## 🚀 Ključne funkcije

### 1️⃣ Inteligentno skeniranje in prepoznava
- **Avtomatsko odkrivanje:** Skeniranje lokalnega omrežja za prikaz vseh povezanih naprav (IP, MAC, Gateway).  
- **Samodejni zajem MAC naslovov:** Aplikacija avtomatsko pridobi MAC naslove preko ARP tabele sistema, kar odpravlja potrebo po ročnem vnosu.  
- **Kategorizacija naprav:** Prepoznavanje tipov naprav (PC, hladilnik, TV, tiskalnik) in prilagajanje nadzornih menijev glede na tip naprave.

### 2️⃣ Shranjena omrežja (Saved Networks)
- **Profili omrežij:** Shranjevanje različnih lokacij (npr. "Dom", "Služba").  
- **Oddaljeno upravljanje:** Možnost nadzora naprav tudi, ko mobilna naprava ni v istem omrežju, preko povezave s centralnim strežnikom.  
- **Status Active/Inactive:** Spremljanje stanja naprav v realnem času na podlagi odzivnosti in "lease time" parametrov.

### 3️⃣ Nadzor in avtomatizacija
- **Wake-on-LAN (WoL):** Vklop ugasnjenih računalnikov preko Magic Packet protokola.  
- **Pametni urniki:** Možnost nastavitve časa za samodejni vklop ali izklop naprave.  
- **Specifične nastavitve:** Prilagajanje temperature za pametne hladilnike ali drugih specifičnih parametrov.

### 📶 Povezljivost
- **Pametna obvestila:** Takojšnje "Pop-up" opozorilo ob zagonu, če mobilna naprava nima internetne povezave.  
- **Samodejna posodobitev:** Osvežitev seznama naprav takoj, ko je povezava ponovno vzpostavljena.

---

## 🛠️ Tehnični sklad (Tech Stack)

### Mobilna aplikacija
- **Okolje:** Android Studio  
- **Jezik:** Kotlin  
- **Baza podatkov:** Room Database (relacijska struktura s tujimi ključi)  
- **Arhitektura:** MVVM (Model-View-ViewModel)  
- **Logika v ozadju:** WorkManager za urnike in BroadcastReceivers za stanje omrežja  

### Strežniška infrastruktura
- **Operacijski sistem:** Linux (Ubuntu Server / Raspberry Pi OS)  
- **Komunikacija:** REST API / MQTT protokol  
- **Varnost:** UFW firewall pravila in Port Forwarding na routerju  

---

## 🔧 Namestitev in konfiguracija

### 1️⃣ Zahteve
1. **BIOS/UEFI:** Na računalnikih omogočite *Wake-on-LAN* ali *Power On by PCI-E*.  
2. **Windows:** V nastavitvah mrežne kartice omogočite "Allow this device to wake the computer".  
3. **Android:** Aplikacija zahteva dovoljenje `CHANGE_WIFI_MULTICAST_STATE` za pošiljanje WOL paketov.  

### 2️⃣ Razvojno okolje
1. Klonirajte projekt:
   ```bash
   git clone https://github.com/lukavuga/fluxio.git
2. Odprite projekt v Android Studiu.  
3. Povežite svojo Android napravo in zaženite projekt.  

---

## 🎨 Grafični vmesnik
- Modern minimalističen dizajn, zasnovan na Material 3 smernicah  
- Barvna paleta: Temen način (Dark Mode) z električno modrimi poudarki  
- Komponente: Zaobljene kartice za naprave, intuitivne ikone in tekoče animacije statusov  

---

## 📜 Licenca
Ta projekt je licenciran pod MIT licenco.

---

## 👨‍💻 Avtor
**Luka Vuga**  
📧 Kontakt: [Vaš Email]  

---

## 📌 Projekt
**Fluxio - Pametni omrežni upravitelj**
