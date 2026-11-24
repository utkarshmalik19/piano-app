
import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class Main {

    private static final String SOUND_DIR = "sounds";

    private static final int FIRST_NOTE = 21;  // A0
    private static final int LAST_NOTE = 108;  // C8

    private static final Map<Integer, String> KEY_NOTES = new HashMap<>();

    private PianoEngine engine;
    private PianoPanel pianoPanel;
    private JLabel statusLabel;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Main().start());
    }

    private void start() {

        setupKeyboard();

        try {
            engine = new PianoEngine();
        } catch (LineUnavailableException e) {
            JOptionPane.showMessageDialog(
                    null,
                    "Unable to initialize audio device: " + e.getMessage()
            );
            return;
        }

        JFrame frame = new JFrame("Java Piano");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setSize(1200, 430);
        frame.setMinimumSize(new Dimension(800, 350));
        frame.setLocationRelativeTo(null);

        statusLabel = new JLabel("Loading piano samples...");
        statusLabel.setBorder(
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        );

        pianoPanel = new PianoPanel(engine);

        frame.setLayout(new BorderLayout());
        frame.add(pianoPanel, BorderLayout.CENTER);
        frame.add(statusLabel, BorderLayout.SOUTH);

        setupKeyBindings(frame);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                engine.close();
            }
        });

        frame.setVisible(true);

        preloadSounds();
    }

    // ------------------------------------------------
    // KEYBOARD MAPPING
    // ------------------------------------------------

    private void setupKeyboard() {

        String[] keys = {
                "A", "W", "S", "E", "D",
                "F", "T", "G", "Y", "H",
                "U", "J", "K", "O", "L",
                "P"
        };

        String[] notes = {
                "C4", "Db4", "D4", "Eb4", "E4",
                "F4", "Gb4", "G4", "Ab4", "A4",
                "Bb4", "B4", "C5", "Db5", "D5",
                "Eb5"
        };

        for (int i = 0; i < keys.length; i++) {

            int keyCode = KeyEvent.getExtendedKeyCodeForChar(
                    keys[i].charAt(0)
            );

            KEY_NOTES.put(keyCode, notes[i]);
        }
    }

    private void setupKeyBindings(JFrame frame) {

        JComponent component = frame.getRootPane();

        for (Map.Entry<Integer, String> entry : KEY_NOTES.entrySet()) {

            int keyCode = entry.getKey();
            String note = entry.getValue();

            String pressAction = "press_" + keyCode;
            String releaseAction = "release_" + keyCode;

            component.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            ).put(
                    KeyStroke.getKeyStroke(keyCode, 0, false),
                    pressAction
            );

            component.getActionMap().put(
                    pressAction,
                    new AbstractAction() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            pianoPanel.pressNote(note);
                        }
                    }
            );

            component.getInputMap(
                    JComponent.WHEN_IN_FOCUSED_WINDOW
            ).put(
                    KeyStroke.getKeyStroke(keyCode, 0, true),
                    releaseAction
            );

            component.getActionMap().put(
                    releaseAction,
                    new AbstractAction() {
                        @Override
                        public void actionPerformed(ActionEvent e) {
                            pianoPanel.releaseNote(note);
                        }
                    }
            );
        }
    }

    // ------------------------------------------------
    // AUDIO PRELOADING
    // ------------------------------------------------

    private void preloadSounds() {

        SwingWorker<Void, Integer> worker =
                new SwingWorker<>() {

                    private final List<String> missingNotes =
                            new ArrayList<>();

                    @Override
                    protected Void doInBackground() {

                        int total = LAST_NOTE - FIRST_NOTE + 1;

                        for (int midi = FIRST_NOTE; midi <= LAST_NOTE; midi++) {

                            String note = midiToNote(midi);

                            try {
                                engine.loadNote(note);
                            } catch (Exception e) {
                                missingNotes.add(note);
                                System.err.println(
                                        "Failed loading " + note + ": "
                                                + e.getMessage()
                                );
                            }

                            publish(midi - FIRST_NOTE + 1);
                        }

                        return null;
                    }

                    @Override
                    protected void process(List<Integer> progress) {

                        int loaded = progress.get(progress.size() - 1);
                        int total = LAST_NOTE - FIRST_NOTE + 1;

                        statusLabel.setText(
                                "Loading piano samples: "
                                        + loaded + " / " + total
                        );
                    }

                    @Override
                    protected void done() {

                        int total = LAST_NOTE - FIRST_NOTE + 1;
                        int loaded = engine.getLoadedCount();

                        statusLabel.setText(
                                "Ready | Loaded: " + loaded + "/" + total
                                        + " | Missing: " + missingNotes.size()
                        );

                        if (!missingNotes.isEmpty()) {
                            System.out.println("Missing notes: " + missingNotes);
                        }
                    }
                };

        worker.execute();
    }

    // ------------------------------------------------
    // NOTE NAMING
    // ------------------------------------------------

    private static final String[] NOTE_NAMES = {
            "C", "Db", "D", "Eb", "E", "F",
            "Gb", "G", "Ab", "A", "Bb", "B"
    };

    public static String midiToNote(int midi) {

        int noteIndex = midi % 12;
        int octave = (midi / 12) - 1;

        return NOTE_NAMES[noteIndex] + octave;
    }

    // ------------------------------------------------
    // PIANO AUDIO ENGINE
    // ------------------------------------------------


    static class PianoEngine {

        private static final int SAMPLE_RATE = 44100;
        private static final int CHANNELS = 2;
        private static final int FRAME_SIZE = 4;
        private static final int BUFFER_FRAMES = 512;

        // Time taken to fade out a note after releasing the key
        private static final int RELEASE_TIME_MS = 450;

        private static final int RELEASE_FRAMES =
                SAMPLE_RATE * RELEASE_TIME_MS / 1000;

        private static final AudioFormat OUTPUT_FORMAT =
                new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        SAMPLE_RATE,
                        16,
                        CHANNELS,
                        FRAME_SIZE,
                        SAMPLE_RATE,
                        false
                );

        private final Map<String, byte[]> samples =
                new ConcurrentHashMap<>();

        private final List<Voice> voices = new ArrayList<>();

        private final Object voiceLock = new Object();

        private final SourceDataLine line;

        private volatile boolean running = true;
        private volatile float volume = 0.5f;

        PianoEngine() throws LineUnavailableException {

            DataLine.Info info = new DataLine.Info(
                    SourceDataLine.class,
                    OUTPUT_FORMAT
            );

            line = (SourceDataLine) AudioSystem.getLine(info);

            line.open(OUTPUT_FORMAT);
            line.start();

            Thread mixerThread = new Thread(
                    this::mixAudio,
                    "Piano-Audio-Mixer"
            );

            mixerThread.setDaemon(true);
            mixerThread.start();
        }

        // ---------------------------------------------
        // LOAD AUDIO
        // ---------------------------------------------

        void loadNote(String note) throws Exception {

            Path path = Paths.get(
                    SOUND_DIR,
                    note + ".mp3"
            );

            if (!Files.exists(path)) {
                throw new FileNotFoundException(path.toString());
            }

            samples.put(note, decodeMp3(path));
        }

        int getLoadedCount() {
            return samples.size();
        }

        // ---------------------------------------------
        // NOTE ON
        // ---------------------------------------------

        void noteOn(String note) {

            byte[] data = samples.get(note);

            if (data == null) {
                return;
            }

            synchronized (voiceLock) {

                // Restart the note if it is already playing
                voices.removeIf(voice -> voice.note.equals(note));

                voices.add(new Voice(note, data));
            }
        }

        // ---------------------------------------------
        // NOTE OFF
        // ---------------------------------------------

        void noteOff(String note) {

            synchronized (voiceLock) {

                for (Voice voice : voices) {

                    if (voice.note.equals(note) && !voice.releasing) {

                        voice.releasing = true;

                        // Capture the current volume envelope
                        voice.releaseStartGain = voice.getCurrentGain();

                        voice.releasePosition = 0;
                    }
                }
            }
        }

        void setVolume(float volume) {
            this.volume = Math.max(0f, Math.min(1f, volume));
        }

        // ---------------------------------------------
        // AUDIO MIXER
        // ---------------------------------------------

        private void mixAudio() {

            byte[] output = new byte[BUFFER_FRAMES * FRAME_SIZE];

            int[] leftMix = new int[BUFFER_FRAMES];
            int[] rightMix = new int[BUFFER_FRAMES];

            while (running) {

                Arrays.fill(leftMix, 0);
                Arrays.fill(rightMix, 0);

                synchronized (voiceLock) {

                    Iterator<Voice> iterator = voices.iterator();

                    while (iterator.hasNext()) {

                        Voice voice = iterator.next();

                        for (int frame = 0;
                             frame < BUFFER_FRAMES;
                             frame++) {

                            // Check whether the sample has finished
                            if (voice.position + 3 >= voice.data.length) {
                                break;
                            }

                            // Check release envelope
                            float envelope = voice.getCurrentGain();

                            if (envelope <= 0f) {
                                break;
                            }

                            int index = voice.position;

                            short left = (short) (
                                    (voice.data[index + 1] << 8)
                                            | (voice.data[index] & 0xff)
                            );

                            short right = (short) (
                                    (voice.data[index + 3] << 8)
                                            | (voice.data[index + 2] & 0xff)
                            );

                            // Apply envelope to audio samples
                            leftMix[frame] += (int) (left * envelope);
                            rightMix[frame] += (int) (right * envelope);

                            // Advance audio sample
                            voice.position += FRAME_SIZE;

                            // Advance release timer
                            if (voice.releasing) {
                                voice.releasePosition++;
                            }
                        }

                        // Remove completed voices
                        if (voice.position >= voice.data.length
                                || (voice.releasing
                                && voice.releasePosition >= RELEASE_FRAMES)) {

                            iterator.remove();
                        }
                    }
                }

                // Convert mixed samples into PCM output
                for (int frame = 0; frame < BUFFER_FRAMES; frame++) {

                    int left = (int) (leftMix[frame] * volume);
                    int right = (int) (rightMix[frame] * volume);

                    // Prevent clipping
                    left = Math.max(-32768, Math.min(32767, left));
                    right = Math.max(-32768, Math.min(32767, right));

                    int index = frame * FRAME_SIZE;

                    output[index] = (byte) left;
                    output[index + 1] = (byte) (left >> 8);

                    output[index + 2] = (byte) right;
                    output[index + 3] = (byte) (right >> 8);
                }

                line.write(output, 0, output.length);
            }
        }

        // ---------------------------------------------
        // MP3 DECODING
        // ---------------------------------------------

        private byte[] decodeMp3(Path path) throws Exception {

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            int sampleRate = -1;
            int channels = -1;

            try (InputStream input = Files.newInputStream(path)) {

                Bitstream bitstream = new Bitstream(input);
                Decoder decoder = new Decoder();

                try {

                    Header header;

                    while ((header = bitstream.readFrame()) != null) {

                        try {

                            SampleBuffer buffer = (SampleBuffer)
                                    decoder.decodeFrame(header, bitstream);

                            sampleRate = buffer.getSampleFrequency();
                            channels = buffer.getChannelCount();

                            short[] pcm = buffer.getBuffer();

                            for (int i = 0;
                                 i < buffer.getBufferLength();
                                 i++) {

                                short sample = pcm[i];

                                output.write(sample & 0xff);
                                output.write((sample >> 8) & 0xff);
                            }

                        } finally {
                            bitstream.closeFrame();
                        }
                    }

                } finally {
                    bitstream.close();
                }
            }

            if (sampleRate <= 0 || channels <= 0) {
                throw new IllegalArgumentException(
                        "Unable to decode MP3: " + path
                );
            }

            AudioFormat sourceFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sampleRate,
                    16,
                    channels,
                    channels * 2,
                    sampleRate,
                    false
            );

            byte[] rawData = output.toByteArray();

            try (AudioInputStream sourceStream =
                         new AudioInputStream(
                                 new ByteArrayInputStream(rawData),
                                 sourceFormat,
                                 rawData.length / sourceFormat.getFrameSize()
                         )) {

                try (AudioInputStream convertedStream =
                             AudioSystem.getAudioInputStream(
                                     OUTPUT_FORMAT,
                                     sourceStream
                             )) {

                    return convertedStream.readAllBytes();
                }
            }
        }

        // ---------------------------------------------
        // CLEANUP
        // ---------------------------------------------

        void close() {

            running = false;

            synchronized (voiceLock) {
                voices.clear();
            }

            line.stop();
            line.close();
        }

        // ---------------------------------------------
        // VOICE
        // ---------------------------------------------

        private static class Voice {

            final String note;
            final byte[] data;

            int position = 0;

            boolean releasing = false;

            int releasePosition = 0;

            float releaseStartGain = 1.0f;

            Voice(String note, byte[] data) {
                this.note = note;
                this.data = data;
            }

            // Calculate current volume envelope
            float getCurrentGain() {

                if (!releasing) {
                    return 1.0f;
                }

                float progress =
                        (float) releasePosition / RELEASE_FRAMES;

                progress = Math.min(progress, 1.0f);

                return releaseStartGain * (1.0f - progress);
            }
        }
    }


    // ------------------------------------------------
    // PIANO UI
    // ------------------------------------------------

    static class PianoPanel extends JPanel {

        private final PianoEngine engine;

        private final Set<String> pressedNotes = new HashSet<>();

        private String mouseNote;

        PianoPanel(PianoEngine engine) {

            this.engine = engine;

            setBackground(new Color(35, 35, 35));
            setPreferredSize(new Dimension(1200, 320));

            MouseAdapter mouseHandler = new MouseAdapter() {

                @Override
                public void mousePressed(MouseEvent e) {
                    mouseNote = findNote(e.getX(), e.getY());
                    pressNote(mouseNote);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    releaseNote(mouseNote);
                    mouseNote = null;
                }

                @Override
                public void mouseDragged(MouseEvent e) {

                    String newNote = findNote(e.getX(), e.getY());

                    if (!Objects.equals(mouseNote, newNote)) {

                        releaseNote(mouseNote);

                        mouseNote = newNote;

                        pressNote(mouseNote);
                    }
                }
            };

            addMouseListener(mouseHandler);
            addMouseMotionListener(mouseHandler);
        }

        void pressNote(String note) {

            if (note == null || !pressedNotes.add(note)) {
                return;
            }

            engine.noteOn(note);
            repaint();
        }

        void releaseNote(String note) {

            if (note == null || !pressedNotes.remove(note)) {
                return;
            }

            engine.noteOff(note);
            repaint();
        }

        // ------------------------------------------------
        // KEYBOARD DRAWING
        // ------------------------------------------------

        @Override
        protected void paintComponent(Graphics g) {

            super.paintComponent(g);

            Graphics2D g2 = (Graphics2D) g.create();

            int whiteCount = 0;

            float whiteWidth = getWidth() / 52f;
            int whiteHeight = getHeight() - 20;

            int blackWidth = Math.max(1, Math.round(whiteWidth * 0.62f));
            int blackHeight = (int) (whiteHeight * 0.62f);

            // Draw white keys first
            for (int midi = FIRST_NOTE; midi <= LAST_NOTE; midi++) {

                if (isBlackKey(midi)) {
                    continue;
                }

                String note = midiToNote(midi);

                int x1 = Math.round(whiteCount * whiteWidth);
                int x2 = Math.round((whiteCount + 1) * whiteWidth);

                g2.setColor(
                        pressedNotes.contains(note)
                                ? new Color(180, 200, 220)
                                : Color.WHITE
                );

                g2.fillRect(
                        x1,
                        10,
                        x2 - x1,
                        whiteHeight
                );

                g2.setColor(Color.GRAY);

                g2.drawRect(
                        x1,
                        10,
                        x2 - x1,
                        whiteHeight
                );

                g2.setColor(Color.DARK_GRAY);
                g2.setFont(new Font("Arial", Font.PLAIN, 10));

                g2.drawString(
                        note,
                        x1 + 3,
                        getHeight() - 25
                );

                whiteCount++;
            }

            // Draw black keys on top
            whiteCount = 0;

            for (int midi = FIRST_NOTE; midi <= LAST_NOTE; midi++) {

                String note = midiToNote(midi);

                if (!isBlackKey(midi)) {
                    whiteCount++;
                    continue;
                }

                int x = Math.round(
                        (whiteCount * whiteWidth) - blackWidth / 2f
                );

                g2.setColor(
                        pressedNotes.contains(note)
                                ? new Color(90, 110, 140)
                                : Color.BLACK
                );

                g2.fillRect(
                        x,
                        10,
                        blackWidth,
                        blackHeight
                );

                g2.setColor(Color.DARK_GRAY);

                g2.drawRect(
                        x,
                        10,
                        blackWidth,
                        blackHeight
                );
            }

            g2.dispose();
        }

        // ------------------------------------------------
        // MOUSE POSITION TO NOTE
        // ------------------------------------------------

        private String findNote(int mouseX, int mouseY) {

            float whiteWidth = getWidth() / 52f;
            int whiteHeight = getHeight() - 20;

            int blackWidth = Math.max(1, Math.round(whiteWidth * 0.62f));
            int blackHeight = (int) (whiteHeight * 0.62f);

            if (mouseY < 10 || mouseY > whiteHeight + 10) {
                return null;
            }

            // Check black keys first because they overlap white keys
            int whiteCount = 0;

            for (int midi = FIRST_NOTE; midi <= LAST_NOTE; midi++) {

                if (!isBlackKey(midi)) {
                    whiteCount++;
                    continue;
                }

                int x = Math.round(
                        (whiteCount * whiteWidth) - blackWidth / 2f
                );

                if (mouseX >= x
                        && mouseX < x + blackWidth
                        && mouseY <= 10 + blackHeight) {

                    return midiToNote(midi);
                }
            }

            // Check white keys
            int index = (int) (mouseX / whiteWidth);

            if (index < 0 || index >= 52) {
                return null;
            }

            whiteCount = 0;

            for (int midi = FIRST_NOTE; midi <= LAST_NOTE; midi++) {

                if (!isBlackKey(midi)) {

                    if (whiteCount == index) {
                        return midiToNote(midi);
                    }

                    whiteCount++;
                }
            }

            return null;
        }

        private boolean isBlackKey(int midi) {

            int pitch = midi % 12;

            return pitch == 1
                    || pitch == 3
                    || pitch == 6
                    || pitch == 8
                    || pitch == 10;
        }
    }
}
