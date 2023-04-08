package me.PabloDevelopment.bootloader.bootstrap;

import com.offbytwo.jenkins.JenkinsServer;
import org.apache.commons.configuration2.YAMLConfiguration;
import org.apache.commons.configuration2.builder.ReloadingFileBasedConfigurationBuilder;
import org.apache.commons.configuration2.builder.fluent.Parameters;
import org.apache.commons.configuration2.ex.ConfigurationException;
import org.apache.commons.configuration2.reloading.PeriodicReloadingTrigger;
import org.apache.commons.configuration2.reloading.ReloadingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class Bootstrap {
    public static YAMLConfiguration config;
    private static final Logger LOGGER = LoggerFactory.getLogger(Bootstrap.class);
    public static JenkinsServer JENKINS;
    public static String JENKINS_PROJECT;
    public static String LATEST_BUILD_BASE_URL;
    public static File BOT_JAR_FILE;
    public static File BOT_JAR_FILE_OLD;
    public static final String VERSION = "1.0";

    //Bot exit codes


    public static final int MAYBE_CORRUPT = 1;
    public static final int NORMAL_SHUTDOWN = 10;
    public static final int RESTART_BOT = 11;
    public static final int UPDATE_BOT = 12;

    public static final int MISSING_JARFILE_NO_JENKINS = 13;
    public static final int DATABASE_CONNECTION_ERROR = 69;
    public static final int INVALID_TOKEN = 99;

    //Bootloader exit codes

    public static final int UNKNOWN_EXIT_CODE = 20;
    public static final int ERROR_COPYING_CONFIG = 21;


    public static List<String> BOT_START_COMMAND = new ArrayList<>(List.of("java"));



    public static void main(String[] args) throws IOException, InterruptedException, URISyntaxException {

        //Copy default configuration file
        File file = new File("bootloaderConfig.yml");
        if(!file.exists()) {
            try(InputStream resourceAsStream = Objects.requireNonNull(Bootstrap.class.getClassLoader().getResourceAsStream(file.getName()))) {
                //noinspection ResultOfMethodCallIgnored
                file.createNewFile();
                Files.copy(resourceAsStream, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }catch (IOException e){
                LOGGER.error("Hubo un error copiando la configuración desde los recursos del jar",e);
                System.exit(ERROR_COPYING_CONFIG);
            }

        }

        //Setup configuration reloading
        try {
            ReloadingFileBasedConfigurationBuilder<YAMLConfiguration> builder = new ReloadingFileBasedConfigurationBuilder<>(YAMLConfiguration.class).configure(new Parameters().hierarchical().setFile(file));
            PeriodicReloadingTrigger trigger = new PeriodicReloadingTrigger(builder.getReloadingController(),
                    null, 20, TimeUnit.SECONDS);
            trigger.start();
            builder.getReloadingController().addEventListener(ReloadingEvent.ANY, event -> {
                try {
                    Bootstrap.config = builder.getConfiguration();
                } catch (ConfigurationException e) {
                    throw new RuntimeException(e);
                }
            });
            Bootstrap.config = builder.getConfiguration();
        } catch (ConfigurationException e) {
            LOGGER.error("There was a configuration exception", e);
        }


        //Init JENKINS's utility settings
        BOT_JAR_FILE = new File(config.getString("Bootloader.jarFilePath", "Bot.jar"));
        BOT_JAR_FILE_OLD = new File(config.getString("Bootloader.oldJarFilePath", "OLD_Bot.jar"));
        JENKINS_PROJECT = config.getString("Jenkins.projectName", "");


        //Init Jenkins
        if(Bootstrap.config.getBoolean("Jenkins.useJenkins", false) && !JENKINS_PROJECT.isEmpty()){
            LATEST_BUILD_BASE_URL = config.getString("Jenkins.server")+ "/job/%s/lastSuccessfulBuild/artifact/build/libs/".formatted(JENKINS_PROJECT);
            JENKINS = new JenkinsServer(new URI(config.getString("Jenkins.server")), config.getString("Jenkins.user", ""),config.getString("Jenkins.token", ""));
        }else LOGGER.error("Necesitas introducir un servidor de JENKINS, la funcionalidad ha sido desactivada.");

        if(!BOT_JAR_FILE.exists()){
            if(!downloadBot()){
                LOGGER.warn("Hubo un error descargando la ultima versión, comprueba que hay espacio libre y que jenkins esté online");
                LOGGER.info("Intentando usar la versión anterior del bot.");
                if(BOT_JAR_FILE_OLD.exists()) {
                    Files.copy(BOT_JAR_FILE_OLD.toPath(), BOT_JAR_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    LOGGER.info("Copia realizada con éxito");
                }else {
                    LOGGER.error("No existe una versión anterior del bot, apagando");
                    System.exit(MISSING_JARFILE_NO_JENKINS);
                }
            }
        }
        //Fetch the custom arguments for the bot's initialization
        BOT_START_COMMAND.addAll(Arrays.asList(config.getStringArray("Bootloader.preArguments")));
        BOT_START_COMMAND.add("-jar");
        BOT_START_COMMAND.addAll(Arrays.asList(config.getStringArray("Bootloader.postArguments")));

        //Start the bot
        LOGGER.info("Iniciando el Bootstrapper loop");
        while (true){
            ProcessBuilder builder = new ProcessBuilder();
            builder.environment().put("BootstrapVersion", VERSION);
            builder.inheritIO();
            if(args.length >= 1){
                BOT_START_COMMAND.add(args[0]);
            }
            builder.command(BOT_START_COMMAND);

            Process botProcess = builder.start();
            botProcess.waitFor();

            switch (botProcess.exitValue()) {
                case NORMAL_SHUTDOWN -> {
                    LOGGER.info("El bot se ha apagado y no se volvera a iniciar.\nApagando...");
                    System.exit(0);
                }
                case RESTART_BOT -> LOGGER.info("El bot se ha apagado debido a reinicio. Reiniciando...");
                case INVALID_TOKEN -> {
                    LOGGER.error("El bot se ha apagado debido a que el token es inválido!!");
                    System.exit(0);
                }
                case UPDATE_BOT -> updateBot();
                case MAYBE_CORRUPT -> {
                    LOGGER.error("El jar actual del bot estaba corrupto, intentando actualizar");
                    if(!updateBot()) break;
                    LOGGER.error("No se pudo descargar una nueva actualización, volviendo a la versión anterior");
                    Files.move(BOT_JAR_FILE_OLD.toPath(), BOT_JAR_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                case DATABASE_CONNECTION_ERROR -> LOGGER.info("Hubo un error conectando con la base de datos. Reiniciando...");
                default -> {
                    LOGGER.error("El bot ha salido con un error desconocido. ExitCode: " + botProcess.exitValue());
                    LOGGER.info("Apagando...");
                    System.exit(UNKNOWN_EXIT_CODE);
                }
            }



        }
    }

    private static boolean updateBot(){
        try{
            Files.move(BOT_JAR_FILE.toPath(), BOT_JAR_FILE_OLD.toPath(), StandardCopyOption.REPLACE_EXISTING);
            if(!downloadBot()){
                Files.move(BOT_JAR_FILE_OLD.toPath(), BOT_JAR_FILE.toPath(), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.error("Se encontró un error mientras se descargaba el bot. Revirtiendo cambios.");
                return false;
            }
            LOGGER.info("Actualización aplicada con éxito!");
            return true;



        } catch (IOException e) {
            LOGGER.error("Se encontró un error mientras se movía el archivo que inicia el bot");
            e.printStackTrace();
            return false;
        }
    }

    @SuppressWarnings("BusyWait")
    private static Boolean downloadBot() throws IOException {
        if(JENKINS == null){
            LOGGER.error("Jenkins no está configurado correctamente");
            return false;
        }
        String jarfile = String.format("%s-%s.jar", JENKINS_PROJECT, JENKINS.getJob(JENKINS_PROJECT).getLastSuccessfulBuild().getNumber());
        for (int i = 0; i < 3 && !BOT_JAR_FILE.exists(); i++) {

            try{
                if(i == 0){
                    LOGGER.info("Intentando descargar actualización, Espere...");
                }
                else{
                    LOGGER.warn("Hubo un error descargando el bot, Esperaré 5 segundos antes de reintentar");
                    Thread.sleep(5000);
                    LOGGER.info(String.format("Intentando descargar actualization, Intento %s, Espere...\n", (i+1)));

                }
                Downloader.file(LATEST_BUILD_BASE_URL + jarfile, BOT_JAR_FILE.getName());
                if(BOT_JAR_FILE.exists()){
                    LOGGER.info("Actualización descargada con éxito");
                    return true;
                }


            } catch (IOException | InterruptedException e) {
                LOGGER.error("Se ha encontrado un error de IO al descargar la actualización");
                e.printStackTrace();
            }

        }
        return false;
    }

}
