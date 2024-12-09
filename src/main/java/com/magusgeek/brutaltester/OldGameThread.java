/*
 * This file was modified by devYaoYH on 18 Nov 2024
 * Modifications:
 *   - Aligned thread to use commands compatible with Fantastic Bits Referee
 *   - Which introduces game-specific parsing of Referee output into the expected player input format
 */

package com.magusgeek.brutaltester;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.magusgeek.brutaltester.util.Mutable;
import com.magusgeek.brutaltester.util.SeedGenerator;

public class OldGameThread extends Thread {

    private static final Log LOG = LogFactory.getLog(OldGameThread.class);

    private Mutable<Integer> count;
    private PlayerStats playerStats;
    private int n;
    private List<BrutalProcess> players;
    private BrutalProcess referee;
    private Path logs;
    private PrintStream logsWriter;

    private ProcessBuilder refereeBuilder;
    private List<ProcessBuilder> playerBuilders;
    private int game;
    private boolean swap;
    private boolean minimal_logging;
    private static final Pattern HEADER_PATTERN = Pattern.compile("\\[\\[(?<cmd>.+)\\] ?(?<lineCount>[0-9]+)\\]");
    private static final Pattern SUMMARY_PATTERN = Pattern.compile("\\$(?<player>\\d+) Score: (?<score>\\d+) \\| Magic: (?<magic>\\d+)");
    
    public static enum InputCommand {
        INIT, GET_GAME_INFO, SET_PLAYER_OUTPUT, SET_PLAYER_TIMEOUT;
        public String format(int lineCount) {
            return String.format("[[%s] %d]", this.name(), lineCount);
        }
    }

    public static enum OutputCommand {
        VIEW, INFOS, NEXT_PLAYER_INPUT, NEXT_PLAYER_INFO, SCORES, UINPUT, TOOLTIP, SUMMARY;
        public String format(int lineCount) {
            return String.format("[[%s] %d]", this.name(), lineCount);
        }
    }

    public OldGameThread(int id, String refereeCmd, List<String> playersCmd, Mutable<Integer> count, PlayerStats playerStats, int n, Path logs, boolean swap,
                         boolean minimal_logging) {
        super("GameThread " + id);
        this.count = count;
        this.playerStats = playerStats;
        this.n = n;
        this.logs = logs;
        this.swap = swap;
        this.minimal_logging = minimal_logging;

        refereeBuilder = new ProcessBuilder(refereeCmd.split(" "));
        playerBuilders = new ArrayList<>();
        for (String cmd : playersCmd) {
            playerBuilders.add(new ProcessBuilder(cmd.split(" ")));
        }
    }

    public void log(String message) {
        if (LOG.isDebugEnabled()) {
            LOG.debug("[Game " + game + "] " + message);
        }

        if (logsWriter != null) {
            logsWriter.println(message);
        }
    }

    public void run() {
        while (true) {
            game = 0;
            synchronized (count) {
                if (count.get() < n) {
                    game = count.get() + 1;
                    count.set(game);
                }
            }

            if (game == 0) {
                // End of this thread
                Main.finish();
                break;
            }

            try {
                if (this.logs != null) {
                    // Open logs stream
                    logsWriter = new PrintStream(this.logs + "/game" + game + ".log");
                }

                // Spawn referee process
                referee = new BrutalProcess(refereeBuilder.start());

                int[] seedRotate = SeedGenerator.getSeed(playerBuilders.size());
                int rotate = swap ? seedRotate[1] : 0;
                // Spawn players process
                players = new ArrayList<>();
                for (int i = 0; i < playerBuilders.size(); i++) {
                    players.add(new BrutalProcess(playerBuilders.get((i + rotate) % playerBuilders.size()).start()));
                }

                log("Attempting to initialize Referee...");

                int numInitLines = 1;
                if (swap || SeedGenerator.repeteableTests) {
                    numInitLines = 2;
                }
                referee.getOut().println(String.format("[[INIT] %d]", numInitLines));
                referee.getOut().println(players.size());
                if (swap) {
                    referee.getOut().println("seed=" + seedRotate[0]);
                }
                else if (SeedGenerator.repeteableTests){
                    referee.getOut().println("seed=" + SeedGenerator.nextSeed());
                }
                referee.getOut().flush();

                // Poll for initial actions
                referee.getOut().println(InputCommand.GET_GAME_INFO.format(0));
                referee.getOut().flush();

                // Starting communication with referee player
                while (!referee.getIn().hasNextLine()) {}
                String line = referee.getIn().nextLine();
                Matcher m = HEADER_PATTERN.matcher(line);
                if (!m.matches()) throw new RuntimeException("Error in data received from referee");
                String cmd = m.group("cmd");
                int lineCount = Integer.parseInt(m.group("lineCount"));
                log("Referee: " + line);

                int currentPlayer = 0;
                StringBuilder initInput = new StringBuilder();
                StringBuilder playerInput = new StringBuilder();
                int[] scoreSummary = new int[players.size()];
                int[] magicSummary = new int[players.size()];
                StringBuilder summaryInput = new StringBuilder();

                while (OutputCommand.valueOf(cmd) != OutputCommand.SCORES) {
                    referee.clearErrorStream(this, "Referee error: ");

                    switch (OutputCommand.valueOf(cmd)) {
                    case SUMMARY:
                        for(int i=0;i<lineCount;++i){
                            String inputLine = referee.getIn().nextLine();
                            Matcher summary = SUMMARY_PATTERN.matcher(inputLine);
                            if (!summary.matches()) {
                                log("Referee SUMMARY: " + inputLine);
                            }
                            else {
                                int playerNumber = Integer.parseInt(summary.group("player"));
                                int playerScore = Integer.parseInt(summary.group("score"));
                                int playerMagic = Integer.parseInt(summary.group("magic"));
                                scoreSummary[playerNumber] = playerScore;
                                magicSummary[playerNumber] = playerMagic;
                            }
                        }
                        break;
                    case NEXT_PLAYER_INPUT: // Collect lines to send to player
                        // Check the first line of player input differently as it could be the team initialization
                        String initLine = referee.getIn().nextLine();
                        int initPlayerInput = Integer.parseInt(initLine);
                        if (initPlayerInput < 2) {
                            initInput.append(initLine + "\n");
                        }
                        else {
                            playerInput.append(initLine + "\n");
                        }
                        for(int i=1;i<lineCount;++i){
                            String inputLine = referee.getIn().nextLine();
                            playerInput.append(inputLine + "\n");
                        }
                        break;
                    case NEXT_PLAYER_INFO: // Find out which player to send to
                        if (lineCount < 3) {
                            throw new RuntimeException(String.format("Exected at least 3 lines from NEXT_PLAYER_INFO, only %d lines received.", lineCount));
                        }
                        currentPlayer = Integer.parseInt(referee.getIn().nextLine());
                        int numExpectedPlayerLines = Integer.parseInt(referee.getIn().nextLine());
                        int maxMillis = Integer.parseInt(referee.getIn().nextLine());
                        for(int i=3;i<lineCount;++i){ // flush other lines
                            line = referee.getIn().nextLine();
                        }
                        // Flush output into player process
                        PrintStream outputStream = players.get(currentPlayer).getOut();
                          // first flush init input if available
                        if (!initInput.isEmpty()) {
                            outputStream.print(initInput.toString());
                        }
                          // next flush score summary
                        summaryInput.append(String.format("%d %d\n", scoreSummary[currentPlayer], magicSummary[currentPlayer]));
                        summaryInput.append(String.format("%d %d\n", scoreSummary[(currentPlayer+1)%players.size()], magicSummary[(currentPlayer+1)%players.size()]));
                        outputStream.print(summaryInput.toString());
                          // finally flush entities output
                        outputStream.print(playerInput.toString());
                        outputStream.flush();
                        log(String.format("Flushing inputs to Player %d:\n%s\n%s\n%s", currentPlayer, initInput.toString(), summaryInput.toString(), playerInput.toString()));
                        referee.getOut().println(InputCommand.SET_PLAYER_OUTPUT.format(numExpectedPlayerLines));
                        referee.getOut().flush();
                        // Wait for child process output
                        BrutalProcess player = players.get(currentPlayer);
                        player.clearErrorStream(this, "Player " + currentPlayer + " error: ");
                        for (int i = 0; i < numExpectedPlayerLines; ++i) {
                            while (!player.getIn().hasNextLine()) {
                                // Wait for timeout duration.
                                player.clearErrorStream(this, "Player " + currentPlayer + " error: ");
                            }
                            String playerLine = player.getIn().nextLine();
                            log("Player " + currentPlayer + ": " + playerLine);
                            referee.getOut().println(playerLine);
                        }
                        // Clear storage buffer for player instructions
                        initInput.setLength(0);
                        playerInput.setLength(0);
                        summaryInput.setLength(0);
                        // Finally query next game info
                        referee.getOut().println(InputCommand.GET_GAME_INFO.format(0));
                        referee.getOut().flush();
                        break;
                    default: // Flush lines from output
                        for(int i=0;i<lineCount;++i) line=referee.getIn().nextLine();
                        break;
                    }

                    // Next line from referee
                    while (!referee.getIn().hasNextLine()) {}
                    line = referee.getIn().nextLine();
                    m = HEADER_PATTERN.matcher(line);
                    if (!m.matches()) throw new RuntimeException("Error in data received from referee");
                    cmd = m.group("cmd");
                    lineCount = Integer.parseInt(m.group("lineCount"));
                    log("Referee: " + line);
                }

                // End of the game
                int[] finalScore = new int[players.size()];
                for (int i = 0; i < lineCount; i++) {
                    String scoreLine = referee.getIn().nextLine();
                    String[] parts = scoreLine.strip().split(" ");
                    int playerNumber = Integer.parseInt(parts[0]);
                    int playerScore = Integer.parseInt(parts[1]);
                    // unswap the positions to declare the correct winner
                    playerNumber = (playerNumber + rotate)%players.size();
                    String rotatedScoreLine = String.format("%d %d", playerNumber, playerScore);
                    log("Referee: " + rotatedScoreLine);
                    finalScore[playerNumber] = playerScore;
                }
                playerStats.add(finalScore);

                if (!minimal_logging)
                    LOG.info("End of game " + game + ": " + "\t" + playerStats);
            } catch (Exception exception) {
                LOG.error("Exception in game " + game, exception);
            } finally {
                destroyAll();
            }
        }
    }

    private void destroyAll() {
        try {
            if (players != null) {
                for (BrutalProcess player : players) {
                    player.destroy();
                }
            }

            if (referee != null) {
                referee.destroy();
            }
        } catch (Exception exception) {
            LOG.error("Unable to destroy all");
        }

        if (logsWriter != null) {
            logsWriter.close();
            logsWriter = null;
        }
    }

}
