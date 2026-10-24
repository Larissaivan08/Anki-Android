# Leitura automática — MED UERJ

A opção **Leitura automática dos cartões** fica no menu da revisão e em **Configurações → Revisão**. Começa desligada e sua escolha é preservada entre sessões.

Quando ativada, lê o texto visível da pergunta e, após revelar o verso, o texto visível da resposta. Também pode ser ativada em um cartão já aberto. Ao desativar, trocar de lado/cartão, sair da revisão ou colocar o aplicativo em segundo plano, a fala anterior é interrompida. Uma resposta de renderização ou inicialização de voz atrasada não deve reativar uma leitura cancelada.

Em **Configurações → Revisão → Voz brasileira para ler os cartões**, escolha Dora, Alex ou Santa. Use **Ouvir exemplo da voz escolhida** para testar sem abrir um cartão. A escolha fica salva.

## Voz e conta

Utiliza **Kokoro-82M v1.0**, voz brasileira **Dora (`pf_dora`, speaker 42)**, com `sherpa-onnx 1.12.29` e idioma `pt-br`. A inferência neural acontece no celular. Modelo, vozes e fonemizador estão incluídos no APK: não precisa de internet, motor de voz externo, conta, chave de API ou assinatura. Os textos dos cartões não são enviados a servidores. A naturalidade e a pronúncia de termos médicos devem ser avaliadas no aparelho; não é a voz do Edge.

A primeira leitura carrega um modelo de aproximadamente 326 MB, mais 1,6 MB de vozes brasileiras. O APK é consideravelmente maior e a geração pode levar vários segundos em aparelhos modestos. O trabalho ocorre fora da interface. Desligar interrompe a reprodução imediatamente e descarta resultados atrasados; a inferência nativa em andamento termina em segundo plano antes de liberar o modelo. Em caso de erro, desligar e ligar permite tentar novamente.

Os dados das outras vozes e os dicionários de outros idiomas são removidos por 	ools/prepare-meduerj-ptbr.py. O script preserva os pesos neurais compartilhados e remapeia os três speakers; esses pesos ainda ocupam aproximadamente 326 MB. A atualização também remove a antiga pasta privada do fonemizador multilíngue.

### Compilar com a voz incluída

Execute `powershell -ExecutionPolicy Bypass -File tools/setup-meduerj-voice.ps1` antes do Gradle. O script baixa os artefatos oficiais com versões fixas e verifica SHA-256; os binários não são versionados no Git. Depois execute `gradlew.bat :AnkiDroid:assemblePlayDebug -Duniversal-apk=true`. Os workflows de compilação também preparam esses arquivos. O usuário do APK não precisa executar nenhuma preparação.

Fontes: [Kokoro](https://huggingface.co/hexgrad/Kokoro-82M), [vozes brasileiras](https://huggingface.co/hexgrad/Kokoro-82M/blob/main/VOICES.md), [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx/tree/v1.12.29). Kokoro e sherpa-onnx usam Apache-2.0; eSpeak NG, usado apenas para fonemização, usa GPL-3.0-or-later. As licenças e atribuições acompanham os assets do aplicativo.

## Preservação dos baralhos

A leitura usa apenas o DOM renderizado de `#qa`, após a fila de renderização ou os hooks de apresentação do cartão. Não lê os campos brutos da nota, o HTML da resposta guardado em JavaScript ou os modelos da coleção. Textos ocultos, scripts, estilos e controles são excluídos. Não realiza OCR de imagens.

Não escreve nos cartões, nos modelos, no CSS dos baralhos, no agendamento ou no banco da coleção. A única preferência persistida é `meduerjAutomaticReadAloud`.

As duas interfaces de revisão são atendidas. Com o modo ligado, a narração substitui a reprodução **automática** dos áudios/TTS embutidos nos cartões, evitando duas vozes automáticas concorrentes. Desligar restaura o comportamento de reprodução normal. O avanço automático continua respeitando sua própria configuração; a leitura não avalia cartões por conta própria.

## Verificação

Testes Kotlin: `AutomaticCardReadAloudTest` e `NeuralReadAloudTest`, com motores simulados, cobrem ativação, cancelamento, callbacks atrasados, troca de cartão, segundo plano, fechamento, falha e nova tentativa do modelo neural, conclusão e textos longos com Unicode.

```text
gradlew.bat :AnkiDroid:testPlayDebugUnitTest --tests com.ichi2.anki.reviewer.AutomaticCardReadAloudTest --tests com.ichi2.anki.reviewer.NeuralReadAloudTest
```

O teste de navegador `tools/test-meduerj-visible-card-text.cjs` exercita a extração real do DOM em Chrome/Chromium. Requer `playwright-core`; `PLAYWRIGHT_MODULE` pode indicar uma instalação existente e `CHROME_PATH`, o executável do navegador.

```text
node tools/test-meduerj-visible-card-text.cjs
```

Foram geradas amostras reais das três vozes com os arquivos brasileiros preparados, idioma pt-br e speakers 0, 1 e 2 pelo executável oficial sherpa-onnx para Windows. O teste tools/test-meduerj-ptbr.py confirma que o grafo neural é idêntico ao original e que apenas as três vozes brasileiras e os dados pt-BR são mantidos. A reprodução, desempenho, memória e interrupção do áudio ainda exigem validação em Android. Os testes simulados do controlador não comprovam qualidade ou compatibilidade do backend nativo no aparelho.
