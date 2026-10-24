# Leitura automática — MED UERJ

Em **Configurações → Revisão → Voz brasileira para ler os cartões**, escolha **Francisca** (padrão) ou **Thalita**. Ambas são vozes femininas `pt-BR` da Microsoft disponíveis no serviço de leitura do Edge. O botão **Ouvir exemplo da voz escolhida** permite testar a escolha.

A leitura automática começa desligada. Quando ativada, lê o texto visível da pergunta e da resposta após a renderização. Ao desativar, trocar de lado/cartão ou sair da revisão, a reprodução anterior é cancelada. Não altera notas, CSS, templates, agendamento ou banco de dados. A tela clássica usa `#content`; a nova usa `#qa`. Imagens não recebem OCR.

## Tempo de início

O modelo Kokoro e o motor local sherpa-onnx foram removidos do APK. Não há carregamento de centenas de megabytes nem inferência local antes de falar.

O aplicativo abre antecipadamente a conexão quando a leitura está ativada e reutiliza o WebSocket entre solicitações. O áudio MP3 chega ao player durante a geração, com buffer inicial de 100 ms. As partes seguintes de cartões longos são solicitadas enquanto a primeira toca. Um cache em memória limitado a 8 MB reaproveita áudio já gerado, separado por voz e texto. Cancelar desbloqueia imediatamente as leituras do fluxo e libera o player.

Instantaneidade absoluta não pode ser garantida para texto ainda não sintetizado: há rede, serviço e inicialização do áudio no aparelho. Nos testes de protocolo em Windows, Francisca enviou o primeiro áudio em 840–1233 ms com conexão nova e 154 ms com conexão reutilizada. Thalita levou aproximadamente 4518 ms no primeiro teste. Esses números medem a chegada do primeiro pacote, não o início de reprodução em Android, e não são uma garantia de desempenho. Francisca é a opção padrão para priorizar início rápido.

## Internet, dados e disponibilidade

Não exige login, assinatura, chave de API ou aplicativo externo. Precisa de internet para gerar áudio novo. O texto visível é enviado ao serviço da Microsoft para síntese; isso é informado no resumo da preferência. O cache é local e somente em memória, descartado quando o processo termina. Texto de cartões não é escrito nos logs.

A integração utiliza o protocolo observado pelo projeto [edge-tts](https://github.com/rany2/edge-tts), sem servidor intermediário. Não é uma API oficial com garantia de disponibilidade e pode exigir atualização quando o serviço mudar. Falhas de conexão são apresentadas ao usuário; não há repetição ilimitada de solicitações.

## Compilação e testes

Compile normalmente com `gradlew.bat :AnkiDroid:assemblePlayDebug -Duniversal-apk=true`. Nenhum modelo ou pacote de voz precisa ser baixado antes da compilação. A pasta local antiga `assets/neural` é explicitamente excluída do empacotamento e não há dependência sherpa-onnx.

Os testes de protocolo verificam o token contra vetores de referência, XML, seleção restrita a vozes brasileiras femininas, quadros binários, reprodução antes do fim da síntese e cancelamento de leituras bloqueadas. Os testes do controlador cobrem troca de voz/cartão, desligamento e callbacks atrasados. `tools/test-meduerj-visible-card-text.cjs` verifica a extração e o template clássico real. Reprodução e latência finais ainda precisam ser avaliadas no celular.
