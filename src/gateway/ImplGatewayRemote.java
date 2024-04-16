package gateway;

import authentication.AuthenticationRemote;
import authentication.ImplAuthenticationRemote;
import authentication.User;
import authentication.UserTypes;
import crypto.HMAC;
import database.DatabaseRemote;
import jdk.jfr.Category;
import sdc.ImplSdcService;
import sdc.SdcService;
import shared.Car;
import shared.CarCategories;
import shared.Message;

import javax.security.auth.login.CredentialNotFoundException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.rmi.NotBoundException;
import java.rmi.RemoteException;

import java.rmi.registry.LocateRegistry;
import java.util.ArrayList;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;

public class ImplGatewayRemote implements GatewayRemote {
    private DatabaseRemote databasesReplicas[];
    private int currentReplica = 0;
    private boolean isLeader;
    public static final int PORT = 20008;
    private final AuthenticationRemote authenticationStub;

    private List<DatabaseRemote> carDatabaseStub = new ArrayList<>();

    private final BigInteger RSA_PUBLIC_KEY;
    private final BigInteger RSA_PRIVATE_KEY;
    private final BigInteger RSA_MODULUS;
    private final SdcService sdcStub;

    public ImplGatewayRemote(AuthenticationRemote authenticationStub, DatabaseRemote carDatabaseStub, Boolean isLeader) throws RemoteException, NotBoundException {
        this.authenticationStub = authenticationStub;
        this.carDatabaseStub = carDatabaseStub;
        this.isLeader = isLeader;
        final var registrySDC = LocateRegistry.getRegistry(ImplSdcService.PORT);
        sdcStub = (SdcService) registrySDC.lookup("sdc");
        final var rsaKeys = sdcStub.getRSAKeys();
        RSA_PUBLIC_KEY = rsaKeys.publicKey();
        RSA_PRIVATE_KEY = rsaKeys.privateKey();
        RSA_MODULUS = rsaKeys.modulus();
    }

    @Override
    public ImplAuthenticationRemote.Credentials login(String email, String password) throws RemoteException {
        System.out.println("enviando ao serviço de autenticação...");
        return authenticationStub.login(email, password);
    }

    @Override
    public Boolean createCar(String renavam, String name, CarCategories category, String yearManufacture, Double price) throws RemoteException {
        System.out.println("enviando ao serviço de banco de dados...");
        final var newCar = new Car(renavam, name, category, yearManufacture, price);
        if(isLeader){
        for(int i = 0;i< databasesReplicas.length; i++){
                if(databasesReplicas[i] != this){
                    databasesReplicas[i].save(newCar);
                }
            }
        }
        nextReplica();
        return true;
    }

    @Override
    public List<Car> list() throws RemoteException {
        System.out.println("enviando ao serviço de banco de dados...");
        getReplica();
        return databasesReplicas[0].list();
    }

    @Override
    public Boolean deleteCar(String renanam) throws RemoteException {
        System.out.println("enviando ao serviço de banco de dados...");
        if(isLeader){
            for (int i = 0; i < databasesReplicas.length; i++) {
                if (databasesReplicas[i] != this) {
                    databasesReplicas[i].delele(renanam);
                    System.out.println("Atualizado na réplica " + i);
                }
            }
        }
        return true;
    }

    @Override
    public Car getCar(String renavam) throws RemoteException {
        System.out.println("enviando ao serviço de banco de dados...");
        getReplica();
        return databasesReplicas[0].get(renavam);
    }

    @Override
    public List<Car> getCarOfName(String name) throws RemoteException {
        System.out.println("enviando ao serviço de banco de dados...");
        getReplica();
        return databasesReplicas[0].getOfName(name);
    }

    @Override
    public List<Car> getCarOfCategory(int category) throws RemoteException {
        CarCategories carCategory = null;
        if (category == 0) {
            carCategory = CarCategories.ECONOMIC;
        }
        if (category == 1) {
            carCategory = CarCategories.INTERMEDIATE;
        }
        if (category == 2) {
            carCategory = CarCategories.EXECUTIVE;
        }
        System.out.println("enviando ao serviço de banco de dados...");
        return databasesReplicas[0].getOfCategory(carCategory);
    }

    @Override
    public Boolean updateCar(String renavam, String name, CarCategories category, String yearManufacture, Double price) throws RemoteException {
        final var updatedCar = new Car(renavam, name, category, yearManufacture, price);
        System.out.println("enviando ao serviço de banco de dados...");
        if(isLeader){
            for (int i = 0; i < databasesReplicas.length; i++) {
                if (databasesReplicas[i] != this) {
                    databasesReplicas[i].update(renavam, updatedCar);
                    System.out.println("Atualizado na réplica " + i);
                }
            }
        }
        getReplica();
        nextReplica();
        return true;
    }

    @Override
    public Car buyCar(String renavam, double price) throws RemoteException {
        final var carToBuy = getCar(renavam);
        if (!carToBuy.getPrice().equals(price)) return null;
        System.out.println("enviando ao serviço de banco de dados...");
        deleteCar(renavam);
        if(isLeader){
            for(int i = 0;i< databasesReplicas.length; i++){
                    if(databasesReplicas[i] != this){
                        databasesReplicas[i].list();

                    }
                }
            }
            getReplica();
            nextReplica();
        return carToBuy;
    }


    private void nextReplica(){
        currentReplica = (currentReplica + 1) % databasesReplicas.length;
    }

    private String getReplica(){
        return "Réplica: " + currentReplica;
    }
  
    @Override
    public Response execute(Message message) throws RemoteException {
        String content = "";
        if (!Objects.isNull(message.getContent())) {
            final var hmacMessage = sdcStub
                    .checkSignMessage(
                            message.getHMAC(),
                            message.getRSA_PUBLIC_KEY(),
                            message.getRSA_MODULUS());
            final String compareSign;
            try {
                compareSign = HMAC.hMac(message.getHMAC_KEY(), message.getContent());
            } catch (NoSuchAlgorithmException | UnsupportedEncodingException | InvalidKeyException e) {
                throw new RuntimeException(e);
            }
            if (!compareSign.equals(hmacMessage)) {
                System.out.println("Autenticação da mensagem inválida");
                return new Response(null, null, null, null, null, null, null);
            }
        }

        switch (message.getType()) {
            case LOGIN: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var loginMessage = rawMessage.split("-");
                final var output = this.login(loginMessage[0], loginMessage[1]);
                int isRegistered = 0; // false
                if (output.isRegistered()) isRegistered = 1;
                content = isRegistered + "-" + output.useType().name();
                break;
            }
            case LIST_CAR: {
                content = this.list().toString();
                break;
            }
            case NUMBER_OF_CARS: {
                final var cars = this.list();
                content = String.valueOf(cars.size());
                break;
            }
            case BUY_CAR: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var carAlreadyExists = this.getCar(rawMessage);
                if (Objects.isNull(carAlreadyExists)) {
                    content = "Carro não existente";
                }
                content = "Compra realizada com sucesso!";
                break;
            }
            case REGISTER_CAR: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var registerFields = rawMessage.split("-");
                final var renavam = registerFields[0];
                final var name = registerFields[1];
                CarCategories category = null;
                final var categoryNumber = registerFields[2];
                if (Integer.parseInt(categoryNumber) == 0) {
                    category = CarCategories.ECONOMIC;
                }
                if (Integer.parseInt(categoryNumber) == 1) {
                    category = CarCategories.INTERMEDIATE;
                }
                if (Integer.parseInt(categoryNumber) == 2) {
                    category = CarCategories.EXECUTIVE;
                }
                final var yearManufacture = registerFields[3];
                final var price = registerFields[4];
                final var success = this.createCar(renavam, name, category, yearManufacture, Double.parseDouble(price));
                if (!success) content = "Não foi possível cadastrar! Tenten novamente";
                content = "Carro com renavam " + renavam + " cadastrado com sucesso";
                break;
            }
            case REMOVE_CAR: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var success = this.deleteCar(rawMessage);
                if (!success) content = "Não foi possível deletar o carro com renavam " + rawMessage;
                content = "Carro com renavam " + rawMessage + " deletado com sucesso!";
                break;
            }
            case UPDATE_CAR: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var updateFields = rawMessage.split("-");
                final var renavam = updateFields[0];
                final var name = updateFields[1];
                CarCategories category = null;
                final var categoryNumber = updateFields[2];
                if (Integer.parseInt(categoryNumber) == 0) {
                    category = CarCategories.ECONOMIC;
                }
                if (Integer.parseInt(categoryNumber) == 1) {
                    category = CarCategories.INTERMEDIATE;
                }
                if (Integer.parseInt(categoryNumber) == 2) {
                    category = CarCategories.EXECUTIVE;
                }
                final var yearManufacture = updateFields[3];
                final var price = updateFields[4];
                final var success = this.updateCar(renavam, name, category, yearManufacture, Double.parseDouble(price));
                if (!success) content = "Não foi possível atualizar o carro " + renavam;
                content = "Carro com renavam " + renavam +  " atualizado com sucesso!";
                break;
            }
            case SEARCH_CAR: {
                final var rawMessage = sdcStub.decryptMessage(message.getContent(), message.getVERNAM_KEY(), message.getAES_KEY());
                final var searchFields = rawMessage.split("-");
                final var option = searchFields[0];
                final var input = searchFields[1];
                System.out.println(option);
                System.out.println(input);
                if (Integer.parseInt(option) == 1) {
                    content = this.getCar(input).toString();
                }
                if (Integer.parseInt(option) == 2) {
                    content = this.getCarOfName(input).toString();
                }
                if (Integer.parseInt(option) == 3) {
                    content = this.getCarOfCategory(Integer.parseInt(input)).toString();
                }
                break;
            }
        }
        final var vernamKey = sdcStub.getVernamKey();
        final var aesKey = sdcStub.getAESKey();
        final var secureContent = sdcStub.encryptMessage(content, vernamKey, aesKey);
        final var hmacKey = sdcStub.getVernamKey();
        final var hmacSecureContent = sdcStub.signMessage(secureContent, hmacKey, RSA_PRIVATE_KEY, RSA_MODULUS);
        return new Response(RSA_PUBLIC_KEY, RSA_MODULUS, secureContent, hmacSecureContent, hmacKey, vernamKey, aesKey);
    }
}
