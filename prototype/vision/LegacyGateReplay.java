import com.spatialmemory.demo.SceneChangeGate;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Run the actual installed gate implementation on recorded VGA grids. */
public final class LegacyGateReplay {
    public static void main(String[] args) throws Exception {
        SceneChangeGate gate = new SceneChangeGate();
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        String line;
        while ((line = input.readLine()) != null) {
            String[] fields = line.split(",");
            long time = Long.parseLong(fields[0]);
            float[] grid = new float[768];
            for (int i=0;i<768;i++) grid[i] = Float.parseFloat(fields[i+1]);
            boolean capture = gate.sample(grid, time, true);
            System.out.println((capture ? "1" : "0") + "\t" + gate.status());
            if (capture) { gate.complete(true); gate.streamRestarted(); }
        }
    }
}
