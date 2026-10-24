# UERJ: ChatGPT com a própria conta

O áudio foi restaurado ao comportamento original do fork anterior às implementações de leitura automática. Foram removidos o leitor adicionado, as preferências de voz, os motores de síntese, o cache de áudio e os clientes de serviços externos de voz. O áudio e o TTS nativos dos baralhos continuam como no AnkiDroid original.

O aplicativo agora se chama **UERJ**. O identificador foi preservado para permitir atualização da instalação existente sem desinstalar nem apagar baralhos.

Na revisão, abra **⋮ → ChatGPT — minha conta**. O item está disponível nas duas interfaces de estudo. Ele abre `https://chatgpt.com/` numa Custom Tab parcial do navegador, mantendo a revisão aberta atrás. No site oficial, escolha **Entrar → Continuar com Google** e use sua conta normalmente. As conversas exibidas são as da própria conta no site. Não há API nem chave a configurar.

O navegador é responsável pelo site e pelo login, com sua sessão normal. O UERJ não pede senhas, não lê cookies ou tokens e não acessa automaticamente conversas. Nenhum texto dos cartões é enviado automaticamente ao ChatGPT. Não há um formulário de login imitado nem um WebView com interceptação de credenciais.

O aplicativo solicita painel inferior ajustável em telas pequenas e painel lateral nas maiores. A apresentação depende do navegador e da versão instalada: alguns podem ampliar a janela ou abrir a Custom Tab inteira, especialmente durante o login. Fechar a janela ou usar Voltar retorna à revisão. Chrome atualizado é priorizado; na ausência de um navegador compatível, o aplicativo informa o problema em vez de lançar o aplicativo externo do ChatGPT.

A sessão e os recursos disponíveis seguem a conta e as regras normais do site ChatGPT; este painel não altera planos ou limites. Isso é diferente de uma integração nativa por OAuth/API: a documentação oficial informa que a autorização de aplicativos não dá acesso ao histórico de conversas. O painel usa diretamente a interface web solicitada pelo usuário.

Fontes oficiais consultadas:
- https://learn.chatgpt.com/docs/auth
- https://learn.chatgpt.com/docs/web
- https://developers.openai.com/cookbook/articles/sign-in-with-chatgpt

Verificação: testes da intenção de abertura, domínio oficial sem conteúdo de cartões, manutenção da sessão normal, solicitação de painel redimensionável e ausência de navegador. O login real com Google deve ser confirmado pelo usuário no aparelho; não foi automatizado nem foram solicitadas credenciais.
