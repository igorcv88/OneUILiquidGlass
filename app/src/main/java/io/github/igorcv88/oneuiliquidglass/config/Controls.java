package io.github.igorcv88.oneuiliquidglass.config;

import java.util.*;

/**
 * Presentation catalogue for every registry key: everyday name, one-line hint, help, where it
 * appears, how its value reads and what the preview can honestly show. Contract data (type,
 * range, default, owner) stays in the generated {@link SettingsRegistry}; nothing here changes a
 * default, clamp or renderer branch.
 */
public final class Controls {
    public enum Surface {
        HEADSUP("headsup", "Pop-up"),
        KEYGUARD("keyguard", "Bloqueio"),
        SHADE("shade", "Central");

        public final String id, label;

        Surface(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public static Surface of(String id) {
            for (Surface s : values()) if (s.id.equals(id)) return s;
            return HEADSUP;
        }
    }

    /** Groups ordered by perceived effect, not by implementation. */
    public enum Section {
        GLASS("Vidro e desfoque"),
        EDGES("Bordas e reflexos"),
        COLOR("Cor e contraste"),
        BEHAVIOR("Comportamento"),
        MOTION("Lente e movimento"),
        LENS_TUNING("Lente dinâmica"),
        /** Central only, with its own opt-in: drawn as one block of its own (SurfacesPage). */
        QUICK_SETTINGS("Controles rápidos"),
        TECHNICAL("Técnico");

        public final String label;

        Section(String label) {
            this.label = label;
        }
    }

    /** STANDARD rows appear in their group; ADVANCED inside "Avançado"; TECHNICAL only in the
     * technical list and search; INTERNAL is state shown elsewhere and never edited as a row. */
    public enum Tier {
        STANDARD,
        ADVANCED,
        TECHNICAL,
        INTERNAL
    }

    public enum Format {
        PX,
        PERCENT_255,
        PERCENT_1,
        GAIN,
        PLAIN,
        MS,
        HZ,
        DP,
        DEGREES,
        COUNT,
        CHOICE,
        TOGGLE,
        COLOR,
        READONLY
    }

    /** What the offline preview can show for a key. */
    public enum Preview {
        /** Drawn with the same quantity the device uses, scaled to the sample. */
        FAITHFUL,
        /** A readable exaggeration of the direction of the effect, not its physics. */
        DIDACTIC,
        /** Timing, gating or measurement: no picture can show it; test on the phone. */
        BEHAVIOR,
        /** Diagnostics, sampling plumbing or state. */
        NONE
    }

    /** Preview channel a key drives; see ui.PreviewParams. */
    public enum Channel {
        BLUR,
        CORE_BLUR,
        LENS,
        DARK_FILL,
        REGION,
        LIGHT_ALPHA,
        LIGHT_TONE,
        RIM,
        GLINT,
        SHEEN,
        OPTICS,
        OUTLINE,
        EDGE_SHADOW,
        VEIL,
        TEXT_SWAP,
        SATURATION,
        COOL,
        LIGHT_ANGLE,
        FLATTEN,
        CORE_DIM,
        PANEL_DIM,
        PILL,
        PILL_BLUR,
        HEADER_GAP,
        TINT,
        NONE
    }

    public static final class Control {
        public final String key, title, hint, help, autoLabel, feature;
        public final Section section;
        public final Tier tier;
        public final Format format;
        public final Preview preview;
        public final Channel channel;
        public final Set<Surface> surfaces;
        /** Choice value -> {label, description}; insertion order is display order. */
        public final Map<String, String[]> choices;

        Control(Builder b) {
            key = b.key;
            title = b.title;
            hint = b.hint;
            help = b.help;
            autoLabel = b.autoLabel;
            feature = b.feature;
            section = b.section;
            tier = b.tier;
            format = b.format;
            preview = b.preview;
            channel = b.channel;
            surfaces = Collections.unmodifiableSet(b.surfaces);
            choices = Collections.unmodifiableMap(b.choices);
        }

        public SettingSpec spec() {
            return SettingsRegistry.get(key);
        }

        public boolean on(Surface s) {
            return surfaces.contains(s);
        }

        /** The compositor reads it only when a shader compiles (helper restart). */
        public boolean needsRestart() {
            return key.startsWith("sf.");
        }
    }

    static final class Builder {
        final String key;
        String title, hint = "", help = "", autoLabel, feature;
        Section section = Section.TECHNICAL;
        Tier tier = Tier.TECHNICAL;
        Format format = Format.PLAIN;
        Preview preview = Preview.NONE;
        Channel channel = Channel.NONE;
        final EnumSet<Surface> surfaces = EnumSet.noneOf(Surface.class);
        final Map<String, String[]> choices = new LinkedHashMap<>();

        Builder(String key, String title) {
            this.key = key;
            this.title = title;
        }

        Builder hint(String s) {
            hint = s;
            return this;
        }

        Builder help(String s) {
            help = s;
            return this;
        }

        Builder in(Section s, Tier t) {
            section = s;
            tier = t;
            return this;
        }

        Builder on(Surface... s) {
            surfaces.addAll(Arrays.asList(s));
            return this;
        }

        Builder all() {
            return on(Surface.values());
        }

        Builder fmt(Format f) {
            format = f;
            return this;
        }

        Builder auto(String label) {
            autoLabel = label;
            return this;
        }

        Builder feature(String f) {
            feature = f;
            return this;
        }

        Builder show(Preview p, Channel c) {
            preview = p;
            channel = c;
            return this;
        }

        Builder choice(String value, String label, String description) {
            choices.put(value, new String[] {label, description});
            return this;
        }
    }

    private static final Map<String, Control> ALL = new LinkedHashMap<>();
    private static final Surface HU = Surface.HEADSUP, KG = Surface.KEYGUARD, SH = Surface.SHADE;
    private static final Section GLASS = Section.GLASS,
            EDGES = Section.EDGES,
            COLOR = Section.COLOR,
            BEHAVIOR = Section.BEHAVIOR,
            MOTION = Section.MOTION,
            LENS = Section.LENS_TUNING,
            QS = Section.QUICK_SETTINGS,
            TECH = Section.TECHNICAL;
    private static final Tier STD = Tier.STANDARD, ADV = Tier.ADVANCED, TEC = Tier.TECHNICAL;
    private static final Preview FAITH = Preview.FAITHFUL,
            DIDACTIC = Preview.DIDACTIC,
            BEHAV = Preview.BEHAVIOR;

    private static Builder c(String key, String title) {
        return new Builder(key, title);
    }

    private static void put(Builder b) {
        if (ALL.put(b.key, new Control(b)) != null) throw new IllegalStateException(b.key);
    }

    static {
        // Vidro e desfoque
        put(c("liveradius", "Desfoque").fmt(Format.PX).in(GLASS, STD).on(HU, KG)
                .hint("Quanto o fundo fica borrado")
                .help("Desfoque do vidro padrão no pop-up. No bloqueio vale também enquanto o"
                        + " desfoque do bloqueio estiver em Automático. Valores baixos deixam o"
                        + " fundo legível; altos, um vidro fosco uniforme.")
                .show(FAITH, Channel.BLUR));
        put(c("liveshaderadius", "Desfoque").fmt(Format.PX).in(GLASS, STD).on(SH)
                .hint("Quanto o fundo fica borrado em cada cartão")
                .help("Desfoque próprio de cada cartão da central no vidro padrão. O painel já"
                        + " borra o fundo inteiro, então o cartão precisa de menos.")
                .show(FAITH, Channel.BLUR));
        put(c("kgblurradius", "Desfoque").fmt(Format.PX).in(GLASS, STD).on(KG)
                .auto("Automático")
                .help("Desfoque dos cartões da tela de bloqueio. Em Automático segue o desfoque"
                        + " do pop-up (vidro padrão) ou o desfoque sob a lente (lente dinâmica).")
                .show(FAITH, Channel.BLUR));
        put(c("semradiuslens", "Desfoque sob a lente").fmt(Format.PX).in(GLASS, STD).on(HU, KG)
                .auto("Igual ao desfoque geral")
                .hint("Pouco desfoque deixa detalhe para a lente curvar")
                .help("Desfoque do sistema sob os cartões com lente dinâmica. Com muito desfoque"
                        + " não sobra detalhe para a refração aparecer.")
                .show(FAITH, Channel.BLUR));
        put(c("semradius", "Desfoque geral").fmt(Format.PX).in(GLASS, ADV).on(SH)
                .auto("Automático")
                .help("Desfoque do sistema nos cartões sem lente quando o vidro padrão não está"
                        + " em uso, como os cartões da central com a lente dinâmica.")
                .show(FAITH, Channel.BLUR));
        put(c("livedarkfill", "Escurecimento").fmt(Format.PERCENT_255).in(GLASS, STD).on(HU)
                .hint("Preto sobre o vidro escuro")
                .help("Camada preta sobre o pop-up no tema escuro. Mais escurecimento aumenta o"
                        + " contraste do texto e esconde o fundo.")
                .show(FAITH, Channel.DARK_FILL));
        put(c("kgdarkfill", "Escurecimento").fmt(Format.PERCENT_255).in(GLASS, STD).on(KG)
                .hint("Preto sobre o vidro escuro")
                .help("Camada preta dentro dos cartões do bloqueio no tema escuro.")
                .show(FAITH, Channel.DARK_FILL));
        put(c("livedarkalpha", "Brilho do vidro escuro").fmt(Format.PERCENT_255).in(GLASS, ADV)
                .on(HU)
                .help("Branco translúcido que dá corpo ao vidro escuro do pop-up.")
                .show(FAITH, Channel.REGION));
        put(c("kgdarkalpha", "Brilho do vidro escuro").fmt(Format.PERCENT_255).in(GLASS, ADV)
                .on(KG)
                .help("Branco translúcido que dá corpo ao vidro escuro do bloqueio.")
                .show(FAITH, Channel.REGION));
        put(c("lightglass", "Opacidade do vidro claro").fmt(Format.PERCENT_1).in(GLASS, STD)
                .on(HU, KG)
                .hint("Usada no tema claro ou sobre fundos claros")
                .help("Opacidade do vidro claro no pop-up e no bloqueio. Mais opaco deixa o"
                        + " cartão mais branco que o app atrás dele.")
                .show(FAITH, Channel.LIGHT_ALPHA));
        put(c("shadelightglass", "Opacidade do vidro claro").fmt(Format.PERCENT_1)
                .in(GLASS, STD).on(SH)
                .hint("Usada no tema claro ou sobre fundos claros")
                .help("Opacidade do vidro claro dos cartões da central, separada do pop-up.")
                .show(FAITH, Channel.LIGHT_ALPHA));
        put(c("shadeblur", "Desfoque próprio por cartão").fmt(Format.TOGGLE).in(GLASS, ADV)
                .on(SH).feature("per_card_blur")
                .help("Cada cartão expandido ganha seu próprio desfoque em vez de usar só o do"
                        + " painel.")
                .show(DIDACTIC, Channel.BLUR));
        put(c("pillglass", "Vidro no resumo de ícones").fmt(Format.TOGGLE).in(GLASS, STD).on(KG)
                .hint("A pílula das notificações que não cabem")
                .help("Vidro claro na pílula de ícones do bloqueio. Desligado, volta a pílula"
                        + " cinza do sistema.")
                .show(FAITH, Channel.PILL));
        put(c("pillblur", "Desfoque do resumo").fmt(Format.PX).in(GLASS, ADV).on(KG)
                .help("Desfoque da pílula de ícones do bloqueio.")
                .show(FAITH, Channel.PILL_BLUR));
        put(c("semalpha", "Véu do desfoque do sistema").fmt(Format.PERCENT_255).in(COLOR, ADV)
                .all().auto("Automático")
                .help("Opacidade da cor aplicada pelo desfoque do sistema. Automático usa a cor"
                        + " de cada material.")
                .show(DIDACTIC, Channel.TINT));

        // Bordas e reflexos
        put(c("sflens", "Refração da borda").fmt(Format.PLAIN).in(EDGES, STD).on(HU)
                .feature("lens")
                .hint("Quanto o fundo se curva perto da borda")
                .help("Força da lente dinâmica no pop-up, em fração do bisel. Acima de 0,5 a"
                        + " borda pode espelhar o conteúdo. Muda sem reiniciar.")
                .show(FAITH, Channel.LENS));
        put(c("kglens", "Refração da borda").fmt(Format.PLAIN).in(EDGES, STD).on(KG)
                .feature("lens").auto("Igual ao pop-up")
                .hint("Quanto o fundo se curva perto da borda")
                .help("Força da lente dinâmica nos cartões do bloqueio. Igual ao pop-up usa a"
                        + " mesma força da refração do pop-up.")
                .show(FAITH, Channel.LENS));
        put(c("liverim", "Contorno").fmt(Format.GAIN).in(EDGES, STD).on(HU).feature("edge")
                .hint("Linha clara em volta do cartão")
                .help("Força da linha externa do pop-up, somada à intensidade das bordas.")
                .show(FAITH, Channel.RIM));
        put(c("kgrim", "Contorno").fmt(Format.GAIN).in(EDGES, STD).on(KG).feature("edge")
                .hint("Linha clara em volta do cartão")
                .help("Força da linha externa dos cartões do bloqueio.")
                .show(FAITH, Channel.RIM));
        put(c("liveglint", "Reflexo").fmt(Format.PERCENT_1).in(EDGES, STD).on(HU)
                .feature("glint")
                .hint("Brilho curto no canto iluminado")
                .help("Reflexo dentro do canto superior esquerdo do pop-up. Alto, cria um"
                        + " segundo arco que lembra uma bolha.")
                .show(FAITH, Channel.GLINT));
        put(c("kgglint", "Reflexo").fmt(Format.PERCENT_1).in(EDGES, STD).on(KG)
                .feature("glint").hint("Brilho curto no canto iluminado")
                .help("Reflexo no canto dos cartões do bloqueio.")
                .show(FAITH, Channel.GLINT));
        put(c("shadeglint", "Reflexo").fmt(Format.PERCENT_1).in(EDGES, STD).on(SH)
                .feature("glint").hint("Brilho curto no canto iluminado")
                .help("Reflexo no canto dos cartões da central.")
                .show(FAITH, Channel.GLINT));
        put(c("liveoptics", "Intensidade das bordas").fmt(Format.GAIN).in(EDGES, ADV).all()
                .feature("edge")
                .help("Ganho geral da luz nas bordas do vidro padrão: contorno, reflexo e"
                        + " brilho interno juntos.")
                .show(FAITH, Channel.OPTICS));
        put(c("livesheen", "Brilho no topo").fmt(Format.PERCENT_1).in(EDGES, ADV).all()
                .feature("edge")
                .help("Brilho suave no alto do cartão que some em direção à base.")
                .show(FAITH, Channel.SHEEN));
        put(c("lightoutline", "Contorno no vidro claro").fmt(Format.GAIN).in(EDGES, ADV)
                .on(HU, SH).feature("edge")
                .help("Contorno claro que marca o fim do cartão quando o vidro é claro sobre"
                        + " um app branco.")
                .show(FAITH, Channel.OUTLINE));
        put(c("lightedge", "Sombra da borda no vidro claro").fmt(Format.GAIN).in(EDGES, ADV)
                .on(HU, SH).feature("edge")
                .help("Sombra de espessura no lado oposto à luz, usada no vidro claro.")
                .show(DIDACTIC, Channel.EDGE_SHADOW));
        put(c("kgoptics", "Material da lente no bloqueio").fmt(Format.CHOICE).in(EDGES, ADV)
                .on(KG)
                .choice("1", "Material do bloqueio", "Desfoque mais largo e cores mais vivas")
                .choice("0", "Material do pop-up", "O mesmo material dos pop-ups")
                .help("Qual material a lente dinâmica usa nos cartões do bloqueio. Muda sem"
                        + " reiniciar.")
                .show(BEHAV, Channel.NONE));
        put(c("huoptics", "Material da lente no pop-up").fmt(Format.CHOICE).in(EDGES, ADV)
                .on(HU)
                .choice("0", "Material do pop-up", "Padrão")
                .choice("1", "Material do bloqueio", "Para comparar no aparelho")
                .help("Permite testar o material do bloqueio nos pop-ups.")
                .show(BEHAV, Channel.NONE));

        // Cor e contraste
        put(c("veilmax", "Proteção de contraste").fmt(Format.PERCENT_1).in(COLOR, Tier.STANDARD)
                .on(HU, SH).feature("tonemap")
                .hint("Véu máximo para manter o texto legível")
                .help("Limite do véu medido sobre o fundo. Mais proteção deixa o texto legível"
                        + " sobre fundos difíceis; menos mantém o vidro mais transparente.")
                .show(FAITH, Channel.VEIL));
        put(c("popuptone", "Contraste medido no pop-up").fmt(Format.TOGGLE).in(COLOR, Tier.STANDARD)
                .on(HU).feature("tonemap")
                .hint("Mede o fundo sob o pop-up")
                .help("O pop-up recebe um véu medido do fundo atrás dele. Desligado, texto"
                        + " branco pode ficar sobre um app branco.")
                .show(FAITH, Channel.VEIL));
        put(c("textswap", "Texto adaptativo").fmt(Format.TOGGLE).in(COLOR, Tier.STANDARD).all()
                .hint("Vidro claro ou escuro conforme o fundo")
                .help("Sobre um fundo do lado oposto ao tema, o cartão vira vidro claro (ou"
                        + " escuro) e troca a cor do texto. Desligado, só o véu é usado.")
                .show(FAITH, Channel.TEXT_SWAP));
        put(c("shadetone", "Proteção de legibilidade").fmt(Format.CHOICE).in(COLOR, Tier.STANDARD).on(SH)
                .feature("tonemap")
                .choice("3", "Adaptativa", "Mede o fundo ao abrir a central")
                .choice("2", "Véu fixo", "Mesmo véu em todos os cartões")
                .choice("1", "Mapa de tons da lente", "Exige a lente dinâmica")
                .choice("0", "Desligada", "Sem proteção")
                .help("Como a central protege o texto sobre apps claros ou escuros.")
                .show(DIDACTIC, Channel.VEIL));
        put(c("paneldim", "Escurecer o painel").fmt(Format.PERCENT_1).in(COLOR, Tier.STANDARD).on(SH)
                .hint("Sobre apps claros, no tema escuro")
                .help("Escurece o fundo da central aberta sobre um app claro no tema escuro.")
                .show(FAITH, Channel.PANEL_DIM));
        put(c("lighttone", "Cor do vidro claro").fmt(Format.COLOR).in(COLOR, ADV).all()
                .help("Cor-base do vidro claro. Branco é o padrão.")
                .show(FAITH, Channel.LIGHT_TONE));
        put(c("stylelo", "Voltar ao vidro escuro abaixo de").fmt(Format.PERCENT_1)
                .in(COLOR, ADV).all()
                .help("Luminância média do fundo abaixo da qual o cartão volta a ser escuro.")
                .show(DIDACTIC, Channel.TEXT_SWAP));
        put(c("stylehi", "Passar ao vidro claro acima de").fmt(Format.PERCENT_1)
                .in(COLOR, ADV).all()
                .help("Luminância média do fundo a partir da qual o cartão vira vidro claro.")
                .show(DIDACTIC, Channel.TEXT_SWAP));
        put(c("shadecap", "Limite para texto claro").fmt(Format.PERCENT_1).in(COLOR, ADV)
                .on(HU, SH)
                .help("Luminância máxima do fundo sob texto claro antes de o véu escurecer.")
                .show(DIDACTIC, Channel.VEIL));
        put(c("shadelift", "Limite para texto escuro").fmt(Format.PERCENT_1).in(COLOR, ADV)
                .on(HU, SH)
                .help("Luminância mínima do fundo sob texto escuro antes de o véu clarear.")
                .show(DIDACTIC, Channel.VEIL));
        put(c("headertone", "Ajustar relógio e data").fmt(Format.TOGGLE).in(COLOR, ADV).on(SH)
                .help("O relógio, a data e a operadora da central seguem o tom do fundo.")
                .show(BEHAV, Channel.NONE));
        put(c("icontone", "Ajustar ícone pequeno").fmt(Format.TOGGLE).in(COLOR, ADV).all()
                .help("O ícone monocromático da notificação acompanha a cor do texto.")
                .show(BEHAV, Channel.NONE));
        put(c("shadescaleveil", "Véu durante animações").fmt(Format.PERCENT_255)
                .in(COLOR, ADV).on(SH)
                .help("Véu de reserva enquanto um cartão da central é redimensionado.")
                .show(BEHAV, Channel.NONE));
        put(c("shadelocal", "Medir cada cartão").fmt(Format.TOGGLE).in(COLOR, ADV).on(SH)
                .help("Cada cartão mede o trecho do fundo atrás dele em vez da tela inteira.")
                .show(BEHAV, Channel.NONE));

        // Comportamento
        put(c("popuplock", "Contraste estável").fmt(Format.TOGGLE).in(BEHAVIOR, ADV)
                .on(HU)
                .help("O tom do pop-up é decidido quando ele chega e só muda quando o fundo muda de"
                        + " verdade, confirmado por uma segunda leitura; a troca é suave. Desligado,"
                        + " cada leitura vale na hora.")
                .show(BEHAV, Channel.NONE));
        put(c("orphandraw", "Tom certo desde o primeiro quadro").fmt(Format.TOGGLE)
                .in(BEHAVIOR, ADV).on(SH)
                .help("Linhas de grupos minimizados recebem o tom da central já no primeiro"
                        + " desenho.")
                .show(BEHAV, Channel.NONE));
        put(c("headergap", "Espaço acima do título").fmt(Format.DP).in(BEHAVIOR, ADV).all()
                .help("Espaço extra entre o cabeçalho e o título de notificações expandidas. 0"
                        + " é o espaçamento original.")
                .show(FAITH, Channel.HEADER_GAP));
        put(c("shadefadecut", "Corte do desfoque ao sumir").fmt(Format.PERCENT_1)
                .in(BEHAVIOR, ADV).on(SH)
                .help("Abaixo desta opacidade o desfoque do cartão é removido de uma vez,"
                        + " evitando uma mancha branca ao fechar a central.")
                .show(BEHAV, Channel.NONE));
        put(c("kgwinblur", "Desfoque da janela no bloqueio parado").fmt(Format.TOGGLE)
                .in(BEHAVIOR, ADV).on(KG)
                .help("Permite as pequenas rampas de desfoque que um toque inicia no bloqueio"
                        + " parado.")
                .show(BEHAV, Channel.NONE));

        // Lente e movimento (faixa capturada do pop-up)
        put(c("livelens", "Faixa de lente no pop-up").fmt(Format.TOGGLE).in(MOTION, ADV).on(HU)
                .feature("lens")
                .hint("Refração capturada quando o fundo está parado")
                .help("No vidro padrão, o pop-up ganha uma faixa refratada na borda enquanto o"
                        + " fundo não se move.")
                .show(DIDACTIC, Channel.LENS));
        put(c("lens", "Curvatura da faixa").fmt(Format.PERCENT_1).in(MOTION, ADV).on(HU)
                .feature("lens")
                .help("Deslocamento da faixa de lente, em fração do bisel.")
                .show(FAITH, Channel.LENS));
        put(c("rimblur", "Desfoque da faixa").fmt(Format.PX).in(MOTION, ADV).on(HU)
                .help("Desfoque aplicado à faixa capturada da borda.")
                .show(DIDACTIC, Channel.NONE));
        put(c("lensmaxage", "Idade máxima da captura").fmt(Format.MS).in(MOTION, ADV).on(HU)
                .help("Uma captura mais antiga que isto não é usada na faixa de lente.")
                .show(BEHAV, Channel.NONE));
        put(c("lenssettle", "Espera após gesto").fmt(Format.MS).in(MOTION, ADV).on(HU)
                .help("Tempo sem a faixa de lente depois de um toque ou gesto.")
                .show(BEHAV, Channel.NONE));
        put(c("lenstransition", "Espera após transição").fmt(Format.MS).in(MOTION, ADV).on(HU)
                .help("Tempo extra depois de um gesto que pode abrir outro app.")
                .show(BEHAV, Channel.NONE));
        put(c("lensgate", "Ocultar a faixa em").fmt(Format.CHOICE).in(MOTION, ADV).on(HU)
                .choice("directional", "Gestos laterais", "Só movimentos horizontais e dois dedos")
                .choice("conservative", "Qualquer toque", "Mais seguro, some com mais frequência")
                .help("Quais toques escondem a faixa de lente para ela não atrasar o movimento.")
                .show(BEHAV, Channel.NONE));

        // Lente dinâmica · valores lidos ao compilar
        String restart = " Entra em vigor ao reiniciar o userspace.";
        put(c("sf.core", "Desfoque interno").fmt(Format.PX).in(LENS, ADV).on(HU)
                .help("Desfoque do miolo do pop-up dentro da lente; 0 desliga." + restart)
                .show(DIDACTIC, Channel.CORE_BLUR));
        put(c("sf.hubloom", "Contenção de brilho").fmt(Format.PLAIN).in(LENS, ADV).on(HU)
                .help("Reduz pontos muito claros no miolo do pop-up; 0 mantém o disco simples."
                        + restart)
                .show(BEHAV, Channel.NONE));
        put(c("sf.edgeblur", "Desfoque no contorno").fmt(Format.PX).in(TECH, TEC).on(HU)
                .help("Desfoque do pop-up já no contorno, numa curva única até o miolo, sem a faixa"
                        + " nítida perto da borda; 0 usa a curva anterior." + restart));
        put(c("sf.taps", "Amostras do pop-up").fmt(Format.COUNT).in(TECH, TEC).on(HU)
                .help("Amostras por pixel no desfoque interno. Mais custa mais GPU." + restart));
        put(c("sf.ramp", "Rampa do pop-up").fmt(Format.PLAIN).in(TECH, TEC).on(HU)
                .help("Profundidade, em biséis, até o desfoque interno completo." + restart));
        put(c("sf.kgcore", "Desfoque interno").fmt(Format.PX).in(LENS, ADV).on(KG)
                .help("Desfoque do miolo dos cartões do bloqueio." + restart)
                .show(DIDACTIC, Channel.CORE_BLUR));
        put(c("sf.kgsat", "Saturação").fmt(Format.GAIN).in(LENS, ADV).on(KG)
                .help("Vivacidade das cores através do vidro do bloqueio." + restart)
                .show(DIDACTIC, Channel.SATURATION));
        put(c("sf.kgcool", "Tom frio").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Leve tom azulado no vidro do bloqueio." + restart)
                .show(DIDACTIC, Channel.COOL));
        put(c("sf.kgline", "Contorno").fmt(Format.GAIN).in(LENS, ADV).on(KG)
                .help("Linha de contorno da lente no bloqueio." + restart)
                .show(DIDACTIC, Channel.RIM));
        put(c("sf.kgrim", "Reflexo do aro").fmt(Format.GAIN).in(LENS, ADV).on(KG)
                .help("Reflexão no aro da lente do bloqueio. É diferente do contorno do vidro"
                        + " padrão." + restart)
                .show(DIDACTIC, Channel.GLINT));
        put(c("sf.kgspec", "Brilho especular").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Ponto de luz na borda do bloqueio." + restart)
                .show(DIDACTIC, Channel.GLINT));
        put(c("sf.kgglow", "Largura do brilho do aro").fmt(Format.GAIN).in(LENS, ADV).on(KG)
                .help("Largura do brilho em volta do aro." + restart)
                .show(DIDACTIC, Channel.RIM));
        put(c("sf.kgtilt", "Direção da luz").fmt(Format.DEGREES).in(LENS, ADV).on(KG)
                .help("Ângulo de onde vem a luz que ilumina a borda." + restart)
                .show(DIDACTIC, Channel.LIGHT_ANGLE));
        put(c("sf.kgflat", "Uniformidade").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Aproxima o brilho do fundo de um nível comum, deixando o vidro mais"
                        + " uniforme." + restart)
                .show(DIDACTIC, Channel.FLATTEN));
        put(c("sf.kglevel", "Nível da uniformidade").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Brilho-alvo usado pela uniformidade." + restart)
                .show(DIDACTIC, Channel.FLATTEN));
        put(c("sf.kgflatcap", "Limite da uniformidade").fmt(Format.PERCENT_1).in(TECH, TEC)
                .on(KG).help("Peso máximo da uniformidade." + restart)
                .show(DIDACTIC, Channel.FLATTEN));
        put(c("sf.kgtone", "Curva de destaque").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Realça as áreas claras através do vidro do bloqueio." + restart)
                .show(DIDACTIC, Channel.FLATTEN));
        put(c("sf.kgcoredim", "Escurecer o miolo").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Reduz o brilho no centro do cartão do bloqueio." + restart)
                .show(DIDACTIC, Channel.CORE_DIM));
        put(c("sf.kgcorecap", "Teto do desfoque interno").fmt(Format.PX).in(TECH, TEC).on(KG)
                .help("Raio máximo efetivo do desfoque interno do bloqueio." + restart));
        put(c("sf.kggradient", "Transição vidro-miolo").fmt(Format.PERCENT_1).in(TECH, TEC)
                .on(KG)
                .help("Transição contínua entre o vidro e o miolo; 0 usa a curva anterior."
                        + restart));
        put(c("sf.kgband", "Largura da transição óptica").fmt(Format.PERCENT_1).in(TECH, TEC)
                .on(KG)
                .help("Faixa, a partir do contorno, em que a refração e o desfoque crescem juntos"
                        + " (fração do raio do canto). 0 usa o modelo anterior; de 0 a cerca de 42%"
                        + " a faixa fica na largura mínima, só acima disso ela aumenta." + restart));
        put(c("sf.kgbodylens", "Lente do miolo").fmt(Format.PERCENT_1).in(TECH, TEC).on(KG)
                .help("Curvatura suave do cartão inteiro: amplia o fundo de forma uniforme."
                        + restart));
        put(c("sf.kgblurexp", "Curva do desfoque na transição").fmt(Format.PLAIN).in(TECH, TEC)
                .on(KG)
                .help("Quanto o desfoque demora a crescer na transição. Maior: menos desfoque"
                        + " e borda mais nítida; menor: mais desfoque e texto mais legível." + restart));
        put(c("sf.kgtaps", "Amostras do bloqueio").fmt(Format.COUNT).in(TECH, TEC).on(KG)
                .help("Amostras por pixel no desfoque do bloqueio." + restart));
        put(c("sf.kgramp", "Rampa do bloqueio").fmt(Format.PLAIN).in(TECH, TEC).on(KG)
                .help("Profundidade até o desfoque interno completo no bloqueio." + restart));
        put(c("sf.shadecap", "Limite para texto claro (lente)").fmt(Format.PERCENT_1)
                .in(LENS, ADV).on(SH)
                .help("Mapa de tons da lente na central: brilho máximo sob texto claro."
                        + restart)
                .show(DIDACTIC, Channel.VEIL));
        put(c("sf.shadelift", "Limite para texto escuro (lente)").fmt(Format.PERCENT_1)
                .in(LENS, ADV).on(SH)
                .help("Mapa de tons da lente na central: brilho mínimo sob texto escuro."
                        + restart)
                .show(DIDACTIC, Channel.VEIL));
        String light = " Vem do fundo logo atrás do contorno: escuro não acende nada, claro entra mais"
                + " fundo. 0 desliga e volta ao desenho anterior." + restart;
        put(c("sf.hulight", "Luz pela borda").fmt(Format.PERCENT_1).in(LENS, ADV).on(HU)
                .help("Luz que entra pelo contorno do pop-up." + light)
                .show(BEHAV, Channel.NONE));
        put(c("sf.kglight", "Luz pela borda").fmt(Format.PERCENT_1).in(LENS, ADV).on(KG)
                .help("Luz que entra pelo contorno dos cartões do bloqueio." + light)
                .show(BEHAV, Channel.NONE));
        put(c("sf.shadelight", "Luz pela borda (lente)").fmt(Format.PERCENT_1).in(LENS, ADV).on(SH)
                .help("Luz que entra pelo contorno dos cartões da central." + light)
                .show(BEHAV, Channel.NONE));
        // Controles rápidos: opt-in próprio e um bloco próprio dentro da Central (SurfacesPage).
        // As chaves qs.* valem na hora; as sf.qs* ao reiniciar o userspace, como a lente dinâmica.
        // Retired: white films over dark backdrops and inactive discs, and the disc opacity (discs
        // keep Samsung's colours). Read-only so older profiles import and export unchanged.
        for (String[] k : new String[][] {
                {"qs.darkfill", "Véu sobre fundo colorido (descontinuado)"},
                {"qs.darkcolor", "Cor do véu escuro (descontinuado)"},
                {"qs.idle", "Disco desligado (descontinuado)"},
                {"qs.active", "Círculos ligados (descontinuado)"}})
            put(c(k[0], k[1]).fmt(Format.READONLY).in(QS, Tier.INTERNAL).on(SH)
                    .help("Preservado em perfis; sem efeito: cápsulas são vidro e os círculos ficam com as cores da Samsung."));
        put(c("qs.lightstyle", "Estilo antigo (descontinuado)").fmt(Format.READONLY)
                .in(QS, Tier.INTERNAL).on(SH).help("Preservado em perfis; ambos os valores usam tinta adaptativa."));
        put(c("qs.lightfill", "Tom sobre fundo claro").fmt(Format.PERCENT_1).in(QS, STD).on(SH)
                .hint("Escurece levemente as cápsulas sobre apps claros")
                .help("Quanto as cápsulas escurecem sobre apps claros, para os textos escuros terem"
                        + " contraste. Sobre fundos escuros ou coloridos elas ficam transparentes. Máximo 18%.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.lightcolor", "Cor do tom sobre fundo claro").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor do tom das cápsulas sobre apps claros.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tilelight", "Discos adaptativos no fundo claro").fmt(Format.TOGGLE).in(QS, STD).on(SH)
                .help("Personaliza o contraste das bolinhas dos controles sobre apps claros, sem tocar nas cápsulas e nos ícones sem disco.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tilelighton", "Disco ligado · fundo claro").fmt(Format.COLOR).in(QS, STD).on(SH)
                .help("Cor dos discos ligados sobre apps claros. Não muda o fundo colorido.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tilelightoff", "Disco desligado · fundo claro").fmt(Format.COLOR).in(QS, STD).on(SH)
                .help("Cor dos discos desligados sobre apps claros.").show(BEHAV, Channel.NONE));
        put(c("qs.tilelightoffalpha", "Opacidade do disco desligado · claro").fmt(Format.PERCENT_1).in(QS, ADV).on(SH)
                .help("Intensidade da cor dos discos desligados, independente do vidro da cápsula.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tilelightonink", "Ícone ligado · fundo claro").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor aplicada apenas a ícones monocromáticos vinculados a discos ligados.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tilelightoffink", "Ícone desligado · fundo claro").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor aplicada apenas a ícones monocromáticos vinculados a discos desligados.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tiledark", "Personalizar discos no fundo colorido").fmt(Format.TOGGLE).in(QS, ADV).on(SH)
                .help("Desligado por padrão: preserva a aparência aprovada nos fundos escuros e coloridos.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tiledarkon", "Disco ligado · fundo colorido").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor do disco ligado no fundo escuro ou colorido.").show(BEHAV, Channel.NONE));
        put(c("qs.tiledarkoff", "Disco desligado · fundo colorido").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor do disco desligado no fundo escuro ou colorido.").show(BEHAV, Channel.NONE));
        put(c("qs.tiledarkoffalpha", "Opacidade do disco desligado · colorido").fmt(Format.PERCENT_1).in(QS, ADV).on(SH)
                .help("Intensidade da cor desligada no fundo escuro ou colorido.").show(BEHAV, Channel.NONE));
        put(c("qs.tiledarkonink", "Ícone ligado · fundo colorido").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor dos ícones monocromáticos ligados, se a personalização colorida estiver ativa.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.tiledarkoffink", "Ícone desligado · fundo colorido").fmt(Format.COLOR).in(QS, ADV).on(SH)
                .help("Cor dos ícones monocromáticos desligados, se a personalização colorida estiver ativa.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.darkrim", "Contorno sobre fundo colorido").fmt(Format.PERCENT_1).in(QS, STD).on(SH)
                .help("Intensidade da linha de luz em volta das cápsulas sobre fundos escuros ou"
                        + " coloridos.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.lightrim", "Contorno sobre fundo claro").fmt(Format.PERCENT_1).in(QS, STD).on(SH)
                .help("Intensidade da linha de luz em volta das cápsulas sobre apps claros.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.outer", "Espessura do slider").fmt(Format.PERCENT_1).in(QS, STD).on(SH)
                .hint("Pílula inteira de brilho e volume")
                .help("Afina a pílula inteira dos sliders. O toque continua na área original da"
                        + " Samsung; 100% é o máximo, o espaço da grade não permite mais grossa.")
                .show(BEHAV, Channel.NONE));
        put(c("qs.track", "Trilho antigo (descontinuado)").fmt(Format.READONLY)
                .in(QS, Tier.INTERNAL).on(SH).help("Preservado em perfis, sem superfície desenhada."));
        put(c("qs.fill", "Espessura do preenchimento").fmt(Format.PERCENT_1).in(QS, STD).on(SH)
                .hint("Parte que se move ao arrastar")
                .help("Largura da parte branca que acompanha o dedo, dentro da cápsula de vidro; a"
                        + " mesma margem vale nas pontas e no mínimo ela fica um círculo. O valor do"
                        + " brilho ou do volume não muda.")
                .show(BEHAV, Channel.NONE));
        put(c("sf.qslight", "Luz pela borda").fmt(Format.GAIN).in(QS, ADV).on(SH)
                .help("Luz que entra pelo contorno das cápsulas, vinda do fundo logo atrás: escuro"
                        + " não acende nada, claro entra mais fundo. 0 desliga." + restart)
                .show(BEHAV, Channel.NONE));
        put(c("sf.qslens", "Refração da borda").fmt(Format.PERCENT_1).in(QS, ADV).on(SH)
                .help("Refração do fundo junto ao contorno das cápsulas, mais forte na borda e"
                        + " sumindo para dentro; 0 desliga." + restart)
                .show(BEHAV, Channel.NONE));
        put(c("sf.qsbody", "Fundo refratado").fmt(Format.PERCENT_1).in(QS, ADV).on(SH)
                .help("Quanto do fundo refratado aparece no contorno. Alto pode formar um anel mais"
                        + " claro ou mais escuro que a cápsula." + restart)
                .show(BEHAV, Channel.NONE));
        put(c("sf.qsdepth", "Alcance da luz").fmt(Format.PX).in(QS, ADV).on(SH)
                .help("Quanto a luz de um fundo branco entra pelo contorno. Fundos mais escuros"
                        + " entram menos." + restart)
                .show(BEHAV, Channel.NONE));
        put(c("qs.edgeblur", "Desfoque da borda").fmt(Format.PX).in(QS, ADV).on(SH)
                .help("Desfoque do fundo que a lente dinâmica lê no contorno. Mais suaviza a luz e"
                        + " a refração; menos deixa a borda mais nítida.")
                .show(BEHAV, Channel.NONE));
        put(c("sf.debug", "Tinta de diagnóstico").fmt(Format.TOGGLE).in(TECH, TEC).all()
                .help("Pinta em magenta e ciano os programas reescritos pela lente. Só para"
                        + " diagnóstico." + restart));

        // Técnico
        put(c("backdrop", "Origem do fundo").fmt(Format.CHOICE).in(TECH, TEC).all()
                .choice("auto", "Automática", "Vidro padrão sem captura")
                .choice("hybrid", "Híbrida", "Captura só a faixa da borda")
                .choice("capture", "Captura completa", "Pode atrasar atrás do movimento")
                .choice("grid", "Grade de teste", "Textura sintética numerada")
                .choice("off", "Sem amostragem", "Somente o desfoque do sistema")
                .help("De onde vem a imagem que o vidro refrata fora da lente dinâmica."));
        put(c("liveblur", "Vidro padrão").fmt(Format.TOGGLE).in(TECH, TEC).all()
                .help("Material desenhado pelo módulo sem root. Desligado, usa a captura."));
        put(c("profile", "Modelo de refração").fmt(Format.CHOICE).in(TECH, TEC).on(HU)
                .choice("lens", "Lente suave", "Inclinação limitada, sem dobras")
                .choice("snell", "Física", "Modelo de Snell com índice de refração")
                .help("Modelo usado na faixa capturada e na captura completa."));
        put(c("refract", "Multiplicador físico").fmt(Format.GAIN).in(TECH, TEC).on(HU)
                .help("Multiplica o deslocamento do modelo físico."));
        put(c("ior", "Índice de refração").fmt(Format.PLAIN).in(TECH, TEC).on(HU)
                .help("Índice óptico do modelo físico. Vidro comum fica perto de 1,5."));
        put(c("disp", "Dispersão de cor").fmt(Format.PERCENT_1).in(TECH, TEC).on(HU)
                .help("Diferença de deslocamento entre vermelho e azul."));
        put(c("rimhz", "Frequência da faixa").fmt(Format.HZ).in(TECH, TEC).on(HU)
                .help("Capturas por segundo da faixa de lente."));
        put(c("blur", "Desfoque da captura").fmt(Format.PX).in(TECH, TEC).all()
                .help("Desfoque sobre a captura completa; 0 mantém a amostra nítida."));
        put(c("sat", "Saturação da captura").fmt(Format.GAIN).in(TECH, TEC).all()
                .help("Saturação do fundo capturado.")
                .show(FAITH, Channel.SATURATION));
        put(c("hz", "Frequência de captura").fmt(Format.HZ).in(TECH, TEC).on(HU)
                .help("Capturas por segundo do pop-up no modo de captura."));
        put(c("hzkg", "Frequência no bloqueio").fmt(Format.HZ).in(TECH, TEC).on(KG)
                .help("Capturas por segundo no bloqueio, quando necessárias."));
        put(c("tint", "Véu da captura").fmt(Format.PERCENT_255).in(TECH, TEC).all()
                .auto("Automático").help("Opacidade do véu sobre a captura."));
        put(c("semcurve", "Curva de cor do sistema").fmt(Format.CHOICE).in(TECH, TEC).all()
                .choice("auto", "Automática", "Neutra sob a lente, herdada nos demais")
                .choice("none", "Nenhuma", "")
                .choice("spatial", "Espacial", "Falhou em alguns firmwares")
                .choice("dim", "Escurecida", "")
                .choice("ultra", "Ultra", "")
                .help("Curva aplicada pelo desfoque do sistema. Alguns firmwares não a"
                        + " desenham."));
        put(c("semshape", "Forma da região").fmt(Format.CHOICE).in(TECH, TEC).all()
                .choice("auto", "Automática", "")
                .choice("single", "Raio único", "")
                .choice("path", "Contorno", "")
                .choice("four", "Quatro cantos", "")
                .choice("none", "Sem forma", "")
                .help("Geometria da região de desfoque do sistema."));
        put(c("kgblurpath", "Desfoque do bloqueio com a lente").fmt(Format.CHOICE)
                .in(TECH, TEC).on(KG)
                .choice("1", "No cartão", "Acompanha o cartão durante gestos")
                .choice("0", "Na view", "Caminho antigo; pode atrasar")
                .help("Onde o desfoque do bloqueio é desenhado com a lente dinâmica."));
        put(c("shadeblurpath", "Desfoque da central").fmt(Format.CHOICE).in(TECH, TEC).on(SH)
                .choice("1", "No cartão", "Segue o esmaecimento do cartão")
                .choice("0", "Na view", "Caminho antigo; pode deixar rastro")
                .help("Onde o desfoque dos cartões da central é desenhado."));
        put(c("perf", "Medir tempo dos ganchos").fmt(Format.TOGGLE).in(TECH, TEC).all()
                .help("Registra no log o tempo gasto pelo módulo. Tem custo; use só para"
                        + " diagnóstico."));
        put(c("trace", "Registro detalhado de janelas").fmt(Format.TOGGLE).in(TECH, TEC).all()
                .help("Registra a árvore de views e janelas. Gera muito log."));

        // State the app shows elsewhere; never an editable row.
        put(c("sfrefract", "Lente dinâmica").fmt(Format.READONLY).in(TECH, Tier.INTERNAL)
                .all().help("Ligada pelo próprio injetor depois de carregar a biblioteca."));
        put(c("sf.optics", "Versão óptica anunciada").fmt(Format.READONLY)
                .in(TECH, Tier.INTERNAL).all()
                .help("Marcador gravado pela biblioteca. Não prova que a lente está ativa."));
        put(c("sf.norewrite", "Modo de reescrita").fmt(Format.READONLY)
                .in(TECH, Tier.INTERNAL).all()
                .help("Definida pelo reinício: ativar a lente limpa este valor; o modo de"
                        + " coleta o liga."));
    }

    public static Control get(String key) {
        return ALL.get(key);
    }

    public static Collection<Control> all() {
        return Collections.unmodifiableCollection(ALL.values());
    }

    /** Keys a person may edit as a control (not the helper's mode, markers or restart state). */
    public static boolean editable(String key) {
        Control c = ALL.get(key);
        SettingSpec s = SettingsRegistry.get(key);
        return c != null
                && s != null
                && c.tier != Tier.INTERNAL
                && s.type != SettingSpec.Type.READONLY
                && !s.helperOwned();
    }

    /** The few controls shown first on a surface, all active in {@code mode}. */
    public static List<String> primary(Surface surface, Mode mode) {
        switch (surface) {
            case KEYGUARD:
                return mode == Mode.DYNAMIC_LENS
                        ? Arrays.asList("kglens", "kgblurradius", "lightglass", "pillglass")
                        : mode == Mode.STANDARD
                                ? Arrays.asList(
                                        "kgblurradius", "kgdarkfill", "lightglass", "kgrim",
                                        "kgglint")
                                : Arrays.asList("lightglass", "textswap");
            case SHADE:
                return mode == Mode.STANDARD
                        ? Arrays.asList(
                                "liveshaderadius", "shadelightglass", "shadeglint", "paneldim",
                                "shadetone")
                        : Arrays.asList("semradius", "shadelightglass", "paneldim", "shadetone");
            default:
                return mode == Mode.DYNAMIC_LENS
                        ? Arrays.asList("sflens", "semradiuslens", "lightglass", "popuptone")
                        : mode == Mode.STANDARD
                                ? Arrays.asList(
                                        "liveradius", "livedarkfill", "lightglass", "liverim",
                                        "liveglint")
                                : Arrays.asList("lightglass", "lens", "textswap");
        }
    }

    /** Effect layers with a product switch on a surface, with everyday names. */
    public static Map<String, String> layers(Surface surface) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("edge", "Bordas");
        m.put("glint", "Reflexo");
        if (surface != Surface.SHADE) m.put("lens", "Lente");
        m.put("tonemap", "Proteção de contraste");
        if (surface == Surface.SHADE) m.put("per_card_blur", "Desfoque por cartão");
        return m;
    }

    private Controls() {}
}
