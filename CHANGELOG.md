# Histórico de versões — DuoPanel Streambox Fix

Mudanças voltadas ao uso do aplicativo. As versões abaixo pertencem ao fork Streambox Fix, não ao DuoPanel original.

## 1.0.6-streambox

Sidebar recolhível no lugar do antigo EDGE no topo e preservação da proporção ao substituir aplicativos.

### Novidades

- **EDGE agora é uma sidebar vertical recolhida**, na borda esquerda ou direita escolhida. Fechada, deixa visível somente uma lingueta de **24 × 56 dp**, em vez de uma barra permanente no topo ou um painel sempre aberto.
- **Abra quando precisar:** toque na lingueta ou puxe para dentro. Para recolher, use o controle de fechar na mesma borda, deslize de volta para a borda ou toque fora do painel.
- O modo **FLOATING** continua disponível, com preservação das posições flutuantes salvas. As duas grades completas de **Todos os apps**, uma por lado, continuam acessíveis.

### Correções

- **Trocar um app não redefine mais a divisão para 50/50.** Tanto pelo histórico quanto por Todos os apps, à esquerda ou à direita, o novo par herda a proporção em uso: 30/70, 70/30 e as demais proporções salvas. Uma preferência antiga do par de destino não substitui essa escolha.
- **A última seleção prevalece.** Respostas atrasadas de uma abertura anterior não reaplicam apps ou proporções antigas nem alteram a detecção de lados invertidos pelo sistema do fabricante para uma seleção posterior.
- **SWAP ocupado não muda apenas as preferências.** Evita que a ordem salva se inverta sem a troca correspondente das janelas. O toque curto continua trocando os aplicativos; segurar por **3 segundos** continua invertendo só a proporção (30↔70; 50 permanece), sem trocar aplicativos ou históricos.
- **Retomada correta após recriação da atividade intermediária** que abre o par, sem callbacks de uma sessão antiga interferindo na atual.
- A sidebar volta recolhida após rotação ou reconstrução e quando o split deixa de estar ativo, sem sobreposições residuais.

### Atualização e compatibilidade

- Versão **`1.0.6-streambox`**, versionCode **`7`**. Pacote preservado: `com.abdullaherturk.duopanel.streamboxfix`; mesma chave de assinatura das versões anteriores do fork, permitindo atualizar sem desinstalar nem apagar preferências.
- **Já usa 1.0.5?** Toque em **Verificar atualizações** na tela principal. O atualizador foi introduzido na 1.0.5; não é uma novidade desta versão.
- **Usa 1.0.4 ou anterior?** Instale manualmente o APK da 1.0.6 disponível nesta release. Depois, as próximas versões poderão ser verificadas pelo aplicativo.
- O download exige tocar em **Baixar**; a instalação exige confirmação no instalador Android. Não há download automático nem instalação silenciosa. O atualizador verifica integridade, pacote, versionCode superior e a assinatura antes de oferecer a instalação.
- O fork continua separado do DuoPanel original; suas preferências não são importadas do original.

### Verificação e limitações

- A validação local das correções, anterior à atualização dos metadados desta versão, passou com **216 testes debug e 216 testes release**, sem falhas, erros ou testes ignorados, e gerou ambos os APKs.
- Antes de publicar, o pipeline do GitHub Actions executa os testes das duas variantes, compila, assina e verifica versão, pacote, assinatura v2 e certificado preservado. A distribuição contém **somente o APK produzido pela CI** e o arquivo **`SHA256SUMS`**. O workflow acrescenta a estas notas o link da execução correspondente.
- O lint ainda contém problemas preexistentes; o relatório da CI permanece não bloqueante, sem novas supressões. Falhas nos testes, na compilação ou na verificação do APK impedem a publicação.
- **Sem teste físico no Streambox/Omoda ou no veículo e sem medição de desempenho nesse hardware.** Encaixes do divisor e larguras mínimas continuam sujeitos ao Android, ao fabricante e aos aplicativos. Testes automatizados não substituem a validação física.

Instale e teste com o veículo estacionado.

[Release 1.0.6](https://github.com/victorhramos-dev/DuoPanel/releases/tag/v1.0.6-streambox) · [Comparar com 1.0.5](https://github.com/victorhramos-dev/DuoPanel/compare/v1.0.5-streambox...v1.0.6-streambox)

## 1.0.5-streambox

### Novidades

- Introdução do atualizador pelo aplicativo: consulta de releases estáveis com a tela principal visível, no máximo uma vez a cada 24 horas, e botão **Verificar atualizações** para consulta manual.
- Download somente com consentimento, com adiamento e cancelamento; sair da tela cancela a operação. Sem serviço periódico, download automático ou instalação silenciosa.
- Verificação de tamanho, SHA-256, pacote, versionCode superior e assinatura igual à instalada. Instalação confirmada pelo Android; autorização de apps desconhecidos quando exigida pelo sistema.

### Compatibilidade e verificação

- VersionCode **6**, mesmo pacote e chave do fork. A 1.0.4 e anteriores precisavam de instalação manual para receber o atualizador.
- Cobertura automatizada do fluxo de atualização nas APIs 24, 26 e 33. Sem validação física no Streambox/Omoda.

[Release 1.0.5](https://github.com/victorhramos-dev/DuoPanel/releases/tag/v1.0.5-streambox) · [Comparar com 1.0.4](https://github.com/victorhramos-dev/DuoPanel/compare/v1.0.4-streambox...v1.0.5-streambox)

## 1.0.4-streambox

### Novidades

- Duas grades completas de aplicativos, abertas imediatamente e dedicadas aos lados esquerdo e direito.
- Naquela versão, **EDGE era uma barra no topo em toda a largura**, com alternância EDGE/FLOATING e preservação das coordenadas flutuantes. A sidebar recolhível só foi introduzida na 1.0.6.
- Inicialização orientada à prontidão, sem espera incondicional de 15 segundos, respeitando preferências automáticas e bloqueio do dispositivo e evitando execuções duplicadas.
- SWAP por toque curto para trocar os aplicativos e pressão contínua de 3 segundos para inverter somente a proporção, com cancelamento e suporte de acessibilidade.

### Compatibilidade e verificação

- VersionCode **5**, pacote preservado. A validação anterior à preparação dos metadados registrou 126 testes debug e geração do APK debug.
- Cobertura automatizada de inicialização, grades, apresentação da barra e SWAP. Sem validação física no Streambox/Omoda ou medição de desempenho no dispositivo.

[Release 1.0.4](https://github.com/victorhramos-dev/DuoPanel/releases/tag/v1.0.4-streambox) · [Comparar com 1.0.3](https://github.com/victorhramos-dev/DuoPanel/compare/v1.0.3-streambox...v1.0.4-streambox)

## 1.0.3-streambox

### Melhorias

- Ajuste direto para a proporção salva, sem parada intermediária forçada em 50/50 nem espera fixa quando não há reenvio do Maps em modo direção.
- Capas limitadas pelo maior eixo, carregamentos simultâneos compartilhados e downloads sem acumular dados codificados em RAM.
- Cache de capas de 4 MiB, limpeza sob pressão de memória, cancelamento de pedidos e liberação de referências durante o ciclo de vida das telas.
- Menos atualizações de texto e alocações na barra; GPS e bússola pausam quando ela fica oculta.

### Verificação e limites

- 78 testes debug e 78 release, incluindo APIs 28, 30 e 33.
- No emulador Android 13, limitar o maior eixo de um PNG 8192×512 a 512 pixels reduziu o bitmap decodificado de 16 MiB para 64 KiB. Isso **não mede a RAM total do aplicativo nem o desempenho físico no Streambox**.
- APK assinado e painel musical em split com Settings verificados no emulador 1920×720; capas HARDWARE também verificadas no APK real. Esses resultados são históricos e não validam alterações posteriores no veículo.

[Release 1.0.3](https://github.com/victorhramos-dev/DuoPanel/releases/tag/v1.0.3-streambox)
