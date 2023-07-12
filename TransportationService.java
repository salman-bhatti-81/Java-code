package com.project.api;



import javax.annotation.PostConstruct;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;
import javax.transaction.Transactional;
import java.io.*;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.List;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.scheduling.annotation.Scheduled;
//import javax.activation.DataSource;
//import javax.mail.util.ByteArrayDataSource;

//import com.sun.istack.internal.ByteArrayDataSource;

@Service
@Component
@EnableScheduling
public class TransportService {

    private static final org.apache.logging.log4j.Logger LOGGER = LogManager.getLogger(TransportService.class);

    @Autowired
    private Repository Repository;


    @Autowired
    private ContainerUpdateSourceBean containerUpdateSourceBean;

    @Value("${tookan.user.id}")
    private Double user_id;

    
    @Autowired
    private ContactRepository contactRepository;

    @Autowired
    private ContainerEventSourceBean containerEventSourceBean;

    @Autowired
    private Environment environment;

    private ObjectMapper mapperObj = new ObjectMapper();

   

    private int shipment_status_delivered = 64;
    Font catFont = new Font(Font.FontFamily.TIMES_ROMAN, 16,
            Font.BOLD);
    Font catContentFont = new Font(Font.FontFamily.TIMES_ROMAN, 14,
            Font.NORMAL);
    Font redFont = new Font(Font.FontFamily.TIMES_ROMAN, 10,
            Font.NORMAL, BaseColor.RED);
    Font subFont = new Font(Font.FontFamily.TIMES_ROMAN, 12,
            Font.BOLD);
    Font subContentFont = new Font(Font.FontFamily.TIMES_ROMAN, 11,
            Font.NORMAL);


    Font mini = new Font(Font.FontFamily.HELVETICA, 4);
    Font miniBold = new Font(Font.FontFamily.HELVETICA, 4, Font.BOLD);
    Font miniGray = new Font(Font.FontFamily.HELVETICA, 4, Font.NORMAL, BaseColor.GRAY);
    Font small = new Font(Font.FontFamily.HELVETICA, 6);
    Font smallBold = new Font(Font.FontFamily.HELVETICA, 6, Font.BOLD);
    Font smallGray = new Font(Font.FontFamily.HELVETICA, 6, Font.NORMAL, BaseColor.GRAY);
    //smallGray.setColor(BaseColor.GRAY);
    Font smallBoldGray = new Font(Font.FontFamily.HELVETICA, 6, Font.BOLD, BaseColor.GRAY);
    //smallBoldGray.setColor(BaseColor.GRAY);
    Font normal = new Font(Font.FontFamily.HELVETICA, 8);
    Font normalBold = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD);
    Font normalGray = new Font(Font.FontFamily.HELVETICA, 8);
    //normalGray.setColor(BaseColor.GRAY);
    Font normalBoldGray = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, BaseColor.GRAY);
    //normalBoldGray.setColor(BaseColor.GRAY);
    Font large = new Font(Font.FontFamily.HELVETICA, 8);
    Font largeBold = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD);
    Font largeBoldBlue = new Font(Font.FontFamily.HELVETICA, 8, Font.BOLD, new BaseColor(36, 106, 180));
    Font heading1 = new Font(Font.FontFamily.HELVETICA, 12);
    Font heading1Bold = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, new BaseColor(106, 206, 242));
    Font heading1BoldBlue = new Font(Font.FontFamily.HELVETICA, 12, Font.BOLD, new BaseColor(36, 106, 180));
    BaseColor lightGray = new BaseColor(211, 211, 211);
    BaseColor lightBlue = new BaseColor(106, 206, 242);

    // For AWS S3

    @Value("${cloud.aws.credentials.accessKey}")
    private String accessKey;

    @Value("${cloud.aws.credentials.secretKey}")
    private String secretKey;

    @Value("${cloud.aws.region}")
    private String region;

    @Value("${cloud.aws.bucketName}")
    private String bucketName;

    private AmazonS3 s3Client;

    @PostConstruct
    private void initializeAmazon() {

        try {
            BasicAWSCredentials credentials = new BasicAWSCredentials(accessKey, secretKey);
            this.s3Client = AmazonS3ClientBuilder.standard()
                    .withCredentials(new AWSStaticCredentialsProvider(credentials))
                    .withRegion(region)
                    .build();

            if (!s3Client.doesBucketExist(bucketName)) {
                s3Client.createBucket(new CreateBucketRequest(bucketName));

                // Verify that the bucket was created by retrieving it and checking its location.
                String bucketLocation = s3Client.getBucketLocation(new GetBucketLocationRequest(bucketName));

            }
        } catch (Exception e) {
            LOGGER.error("Amazon initialization / Bucket creation,detection issues ", e);
        }
    }

    public Iterable<Transport> getTransportList() {

        Iterable<Transport> carriers = transportRepository.findAllByOrderByRequiredDateAsc();

        return carriers;
    }


   


    private long syncAcceptedTaskSchedulerTime = Constant.TIME_ONE_HOUR_MILISECONDS * 2;

   


    @Transactional
    public ResponseEntity<Transport> createTransportRequest(Transport aTransport) throws ObjectNotFoundException {
        LOGGER.info("mapping transport to Hashmap");
        HashMap<String, Object> obj = mapperObj.convertValue(aTransport, HashMap.class);
        Location location = null;


        //TODO: to unomment the below line
        transportRepository.save(aTransport);
        transportRepository.flush();
        LOGGER.info("Transport object saved without number");
        //TODO: to unomment the below line
        aTransport.setTransportRequestNumber(generateTransportRequestNumber(aTransport.getId()));
        //TODO: to comment the below line
//        aTransport.setTransportRequestNumber("TR-12123");
        //--- save again with the generated number
        //TODO: to uncomment the below 2 lines
        transportRepository.save(aTransport);
        transportRepository.flush();
        LOGGER.info("Transport object saved with number" + aTransport.getTransportRequestNumber());
        Set<Container> containers = new HashSet<Container>();
        Set<Trip> tripSet = new HashSet<Trip>();
        Iterator<Trip> tripIterator = aTransport.getTrips().iterator();
        int counter = 1;
        String transportNumber = aTransport.getTransportRequestNumber();

        transportNumber = transportNumber.substring(transportNumber.lastIndexOf("-") + 1);
        while (tripIterator.hasNext()) {
            Trip trip = tripIterator.next();
            trip.setTransport(aTransport);
            // setting the trip number by adding only integer and appending the counter
            trip.setTripNumber(company_name + "-" + transportNumber + counter++);
            //-- set tenant uuid
            trip.setTenantUuid(getTenentUUID());
            tripSet.add(trip);
            containers.add(trip.getContainer());
        }

        try {
            //TODO: to uncomment below 3 lines
            containerRepository.save(containers);
            tripRepository.save(tripSet);
            tripRepository.flush();
            LOGGER.info("Trips saved. Now calling Tookan service");
            //QAF-282   - SB
            //create trips in Tookan third party
            //TODO: to be uncommented
            createTripsInTookan(tripSet);

        } catch (Exception e) {
            e.printStackTrace();
        }
        //TODO: to remoe below comment - not working on it.
        //transportRepository.save(aTransport);

        //TODO to uncomment below if body
        if (Constant.TRANSPORT_REQUEST_STATUS_SCHEDULED_CODE.equalsIgnoreCase(aTransport.getStatus())) {
            //TODO to uncomment below line
            TransportObjectModel transportObjectModel = new TransportObjectModel("CREATE", this.toTransport(aTransport));
            LOGGER.info("New TransportObject Model Created in createTransportRequestObject Function");
            try {

                if (aTransport.getShipper() != null)
                    aTransport.getShipper().setId(null);
            } catch (Exception e) {
                LOGGER.error("Error while creating transport object model in order to send kafka call");
            }
            System.out.println("\ntransport Created\n");
            LOGGER.info("Transport Created"); //TODO: FIXME
            transportSourceBean.updateShipmentWall(transportObjectModel);
        }
        //send transport request to search-service -Ammar
        //--todo to uncomment below 3 lines
        kafkaAsynService.sendTransport(aTransport);
        this.sendAddAudits(obj, "SET", "Transport", aTransport.getShipmentNo());
        this.logAudit("addTransport", "", "Transport " + " Added", "CREATE", "Transport", aTransport.getShipmentNo());
        //TODO: to uncomment below line
        return new ResponseEntity<Transport>(aTransport, HttpStatus.OK);
        //TODO: to comment below 1 line
//        return new ResponseEntity<Transport>(new Transport(), HttpStatus.OK);
    }


   

   

   

    byte[] bindPDFContentAndType(byte[] one, byte[] two) {
        byte[] content = new byte["data:application/pdf;base64,".length() + two.length]; //"data:application/pdf;base64,".getBytes() + out.toByteArray();
        int i = 0;
        for (; i < one.length; i++) {
            content[i] = one[i];
        }

        for (i = 0; i < two.length; i++) {
            content[i + one.length] = two[i];
        }

        return content;
    }

    byte[] createTruckDriverAssignmentDocument(Transport transport, Driver driver, Truck truck, Container container, Trip trip) {

        Document document = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String destination = "";
        try {

            PdfWriter.getInstance(document, out);
            document.open();
            Anchor anchor = new Anchor("Transport request Type", catFont);
            anchor.setName("First Chapter");
            Paragraph headingPara = new Paragraph("Transport Request Type", catFont);
            Paragraph headingContent = new Paragraph("*******", catContentFont);
            //request type
            String requestType = transport.getType();
            
            document.add(headingPara);
            document.add(headingContent);
            Paragraph paragraph = new Paragraph();
            addEmptyLine(paragraph, 2);
            document.add(paragraph);
            Paragraph contentheading = new Paragraph("Container type", subFont);
            Paragraph contentText = new Paragraph(container.getContainerTypeCode(), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("Driver name", subFont);
            contentText = new Paragraph(driver.getFirstName() + driver.getLastName() != null ? " " + driver.getLastName() : "", subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("Truck number", subFont);
            contentText = new Paragraph(truck.getRegistrationNumber(), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("Destination", subFont);
            contentText = new Paragraph(destination, subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("Time of requirement", subFont);
            if (transport.getRequiredTime()!= null)
                contentText = new Paragraph(transport.getRequiredTime().toString(), subContentFont);

            document.add(contentheading);
            document.add(contentText);

            contentheading = new Paragraph("weightment", subFont);
            contentText = new Paragraph(String.valueOf(trip.isWeightment()), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("food Grade", subFont);
            contentText = new Paragraph(String.valueOf(trip.isFoodGrade()), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("heavy duty", subFont);
            contentText = new Paragraph(String.valueOf(trip.isHeavyDuty()), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            contentheading = new Paragraph("ground", subFont);
            contentText = new Paragraph(String.valueOf(trip.isGround()), subContentFont);
            document.add(contentheading);
            document.add(contentText);
            paragraph = new Paragraph();
            addEmptyLine(paragraph, 4);
            document.add(paragraph);
            contentheading = new Paragraph("Customer signature", subFont);
            document.add(contentheading);
            paragraph = new Paragraph();
            addEmptyLine(paragraph, 2);
            document.add(paragraph);
            contentText = new Paragraph("....................................................", subContentFont);

            document.add(contentText);
            document.close();
        } catch (DocumentException ex) {

            Logger.getLogger(CreateTrip.class.getName()).log(Level.SEVERE, null, ex);
        }
        return out.toByteArray();
    }

    public Iterable<Trip> getTripsByTransportId(Long request_id) {
        return tripRepository.findAllByTransport(transportRepository.findOne(request_id));
    }

    public byte[] driversReport(Transport transport, Driver driver, Truck truck, Container container, Trip trip) throws IOException {

       

        Document document = new Document(PageSize.A4);
        document.setMargins(46.0F, 36.0F, -6F, 36.0F);
        ByteArrayOutputStream out = new ByteArrayOutputStream();


        try {
            Image img;

            if (company_name.equals("qafila")) {
                img = Image.getInstance("classpath:images/qafila-logo-email-signature.png");

            } else {
                img = Image.getInstance("classpath:images/Qafila-logo-email-signature.png");

            }

            img.scaleToFit(100f, 40f);

            PdfWriter.getInstance(document, out);
            document.open();
            Paragraph extraLines = new Paragraph();

            PdfPTable headerTable = new PdfPTable(4);
            headerTable.getDefaultCell().setBorder(0);
            headerTable.setWidthPercentage(100);

            PdfPCell logo = new PdfPCell(img);
            logo.setPaddingTop(65);
            logo.setPaddingBottom(0);
            logo.setBorder(0);
            headerTable.addCell(logo);
            img = Image.getInstance("classpath:images/watermark.PNG");
            img.scaleToFit(140f, 240f);
            PdfPCell watermark = new PdfPCell(img);
            watermark.setPaddingBottom(0);
            watermark.setHorizontalAlignment(Element.ALIGN_RIGHT);
            watermark.setPaddingRight(-40);
            watermark.setBorder(0);
            headerTable.addCell(new Paragraph(" "));
            headerTable.addCell(new Paragraph(" "));
            headerTable.addCell(watermark);

            document.add(headerTable);

            // add watermark

            PdfPTable table = new PdfPTable(2);
            table.getDefaultCell().setBorder(0);
            table.setWidthPercentage(100);
            table.setWidths(new float[]{2, 1});


            Paragraph first = new Paragraph("Al qafila Shipping Co. LLC", normalBold);
            Paragraph second;// = new Paragraph("Shipment Number: QAF-18001015",normal);
            PdfPCell leftCell = new PdfPCell(first);

            Chunk chunk = new Chunk("Shipment Number: ", normalBoldGray);
            second = new Paragraph();
            second.add(chunk);
            chunk = new Chunk(transport.getShipmentNo(), normal);
            second.add(chunk);
            PdfPCell rightCell = new PdfPCell(second);
            leftCell.setPaddingLeft(10);
            leftCell.setBorder(0);
            //set border left color
            leftCell.setUseVariableBorders(true);
            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);
            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);
            rightCell.setPaddingLeft(10);
            table.addCell(leftCell);
            table.addCell(rightCell);

            chunk = new Chunk("Address:", normalBoldGray);
            first = new Paragraph();
            first.add(chunk);

            leftCell = new PdfPCell(first);
            leftCell.setPaddingLeft(10);


            chunk = new Chunk("Trip Date: ", normalBoldGray);
            second = new Paragraph();
            second.add(chunk);
            String formattedDate = new SimpleDateFormat("dd/MM/yyyy").format(transport.getCutOffDate());
            chunk = new Chunk(formattedDate, normal);
            second.add(chunk);
            rightCell = new PdfPCell(second);
            leftCell.setBorder(0);

            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);
            rightCell.setBorder(0);
            //rightCell.setPaddingBottom(0);
            rightCell.setPaddingLeft(10);
            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);
            //leftCell.setFixedHeight(2);
            table.addCell(leftCell);
            table.addCell(rightCell);
            float height = leftCell.getHeight();

            chunk = new Chunk("151 Khalid Bin Walid Road, Umm Hurrair 1, Dubai,", normal);
            first = new Paragraph();
            first.add(chunk);
            second = new Paragraph(" ", mini);
            leftCell = new PdfPCell(first);
            leftCell.setPaddingTop(10);
            ;

            //leftCell.setPaddingLeft(10);
            rightCell = new PdfPCell(second);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);

            leftCell.setBorder(0);
            //set border left color
            leftCell.setPaddingLeft(10);
            leftCell.setPaddingTop(0);
            ;
            leftCell.setPaddingBottom(0L);
            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);
            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);

            rightCell.setPaddingTop(0);
            table.addCell(leftCell);
            table.addCell(rightCell);


            first = new Paragraph("United Arab Emirates", normal);
            second = new Paragraph(" ", mini);
            leftCell = new PdfPCell(first);
            //leftCell.setPaddingLeft(10);
            rightCell = new PdfPCell(second);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            leftCell.setBorder(0);
            leftCell.setPaddingLeft(10);
            leftCell.setPaddingTop(0);
            ;
            leftCell.setPaddingBottom(0L);
            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);


            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);
            rightCell.setPaddingBottom(0);
            rightCell.setPaddingTop(0);
            table.addCell(leftCell);
            table.addCell(rightCell);

            chunk = new Chunk("Phone: ", normalBoldGray);
            first = new Paragraph();
            first.add(chunk);
            chunk = new Chunk("121212 ", normal);
            first.add(chunk);
            second = new Paragraph(" ", mini);
            leftCell = new PdfPCell(first);
            //leftCell.setPaddingLeft(10);
            rightCell = new PdfPCell(second);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            leftCell.setBorder(0);
            //set border left color
            leftCell.setUseVariableBorders(true);
            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);
            leftCell.setPaddingTop(0);
            ;
            leftCell.setPaddingBottom(0L);
            leftCell.setPaddingLeft(10);
            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);
            rightCell.setPaddingBottom(0);
            rightCell.setPaddingTop(0);
            table.addCell(leftCell);
            table.addCell(rightCell);


            /*Email*/
            chunk = new Chunk("Email: ", normalBoldGray);
            first = new Paragraph();
            first.add(chunk);
            chunk = new Chunk(company_contact_email, normal);

            first.add(chunk);
            second = new Paragraph(" ", mini);
            leftCell = new PdfPCell(first);
            leftCell.setPaddingLeft(10);
            rightCell = new PdfPCell(second);
            rightCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            leftCell.setBorder(0);
            //set border left color
            leftCell.setUseVariableBorders(true);
            leftCell.setBorderWidthLeft(1);
            leftCell.setBorderColorLeft(lightBlue);
            rightCell.setBorder(0);
            //set border left color
            rightCell.setUseVariableBorders(true);
            rightCell.setBorderWidthLeft(1);
            rightCell.setBorderColorLeft(lightBlue);
            table.addCell(leftCell);
            table.addCell(rightCell);

            //first.add("Al qafila Shipping Co. LLC",normalBold);

            document.add(table);
            /*Paragraph paragraph=new Paragraph();
            addEmptyLine(paragraph, 2);
            document.add(paragraph);*/
            document.add(addEmptyLineWithBorder(2));
            document.add(getDriverInfoTtableForTrip(transport, driver, truck, trip));
            Paragraph paragraph = new Paragraph();
            addEmptyLine(paragraph, 11);
            document.add(paragraph);
            document.add(getNotesAndSignatureSection());
            //document.add(first);

            document.close();

        } catch (DocumentException ex) {

            Logger.getLogger(CreateTrip.class.getName()).log(Level.SEVERE, null, ex);
        } /*catch (javax.mail.MessagingException e) {
            e.printStackTrace();
        }catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }*/

        //For test purpose. below commented code show complete document
        /*FileResponse fileResponse = new FileResponse();
        fileResponse.setContent(out.toByteArray());
        fileResponse.setContentType("data:application/pdf;base64,");
        return fileResponse;*/
        return out.toByteArray();
    }

    private PdfPTable getDriverInfoTtableForTrip(Transport transport, Driver driver, Truck truck, Trip trip) throws DocumentException {

        String formattedDate;
        if (transport != null && driver != null && truck != null) {
            PdfPTable table = new PdfPTable(3);
            table.getDefaultCell().setBorder(0);
            //table.getDefaultCell().setBackgroundColor(lightGray);
            table.setWidthPercentage(100);

            PdfPCell firstCell;
            PdfPCell secondCell = new PdfPCell(new Paragraph());
            PdfPCell thirdCell = new PdfPCell(new Paragraph());
            secondCell.setBackgroundColor(BaseColor.WHITE);
            thirdCell.setBackgroundColor(BaseColor.WHITE);
            //Heading
            Paragraph first = new Paragraph();

            Chunk chunk = new Chunk("Trip Details - ", heading1BoldBlue);
            first.add(chunk);
            chunk = new Chunk("For Driver", heading1Bold);
            first.add(chunk);
            firstCell = new PdfPCell(first);

            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setPaddingLeft(10);
            firstCell.setBorderColorLeft(lightBlue);
            Paragraph second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            Paragraph third = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            secondCell.setBorder(0);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(secondCell);
            table.addCell(thirdCell);

            //add one empty line
            firstCell = new PdfPCell(new Paragraph(" "));
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);

            thirdCell = new PdfPCell(new Paragraph(" "));
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            table.addCell(firstCell);
            table.addCell(new Paragraph(" "));
            table.addCell(thirdCell);
            // details
            first = new Paragraph();


            chunk = new Chunk("Driver Name:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk(driver.getFirstName() + driver.getLastName() != null ? " " + driver.getLastName() : "", normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            firstCell.setPaddingLeft(10);
            //firstCell.setBackgroundColor(lightGray);
            second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            third = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            chunk = new Chunk("Cut Off Date/Time:  \n\t", normalBoldGray);
            third.add(chunk);
            formattedDate = new SimpleDateFormat("dd/MM/yyyy").format(transport.getCutOffDate());
            chunk = new Chunk(formattedDate + " " + transport.getCutOffTime(), normal);
            third.add(chunk);
            thirdCell = new PdfPCell(third);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(second);
            table.addCell(thirdCell);


            first = new Paragraph();

            chunk = new Chunk("Truck Registration Number:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk(truck.getRegistrationNumber(), normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setPaddingLeft(10);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            //firstCell.setBackgroundColor(lightGray);
            second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            third = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            chunk = new Chunk("Required Date/Time:  \n\t", normalBoldGray);
            third.add(chunk);
            if (trip.getEstimatedDateCustomer() != null) {
                formattedDate = new SimpleDateFormat("dd/MM/yyyy").format(trip.getEstimatedDateCustomer());
                chunk = new Chunk(formattedDate + " " + trip.getEstimatedTimeCustomer(), normal);
            } else {
                chunk = new Chunk(formattedDate + " N/A", normal);
            }

            third.add(chunk);
            thirdCell = new PdfPCell(third);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(second);
            table.addCell(thirdCell);

            first = new Paragraph();

            chunk = new Chunk("Trailer Type:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk("20 DC", normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setPaddingLeft(10);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            //firstCell.setBackgroundColor(lightGray);
            second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            third = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            chunk = new Chunk("Customer Location:  \n\t", normalBoldGray);
            third.add(chunk);
            if (trip.getCustomerLocation() != null)
                chunk = new Chunk(trip.getCustomerLocation().getAddress1(), normal);
            else
                chunk = new Chunk("N/A", normal);
            third.add(chunk);
            thirdCell = new PdfPCell(third);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(second);
            table.addCell(thirdCell);


            first = new Paragraph();

            chunk = new Chunk("", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk("", normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setPaddingLeft(10);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            //firstCell.setBackgroundColor(lightGray);
            second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            third = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            chunk = new Chunk("Request Type:  \n\t", normalBoldGray);
            third.add(chunk);
            chunk = new Chunk(getTransportRequestStatus(transport.getType()), normal);
            third.add(chunk);
            thirdCell = new PdfPCell(third);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(second);
            table.addCell(thirdCell);


            first = new Paragraph(" ");
            firstCell = new PdfPCell(first);
            //set border left color
            firstCell.setBorder(0);
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            second = new Paragraph(" ");
            third = new Paragraph(" ");
            thirdCell = new PdfPCell(third);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            thirdCell.setPaddingLeft(10);
            thirdCell.setPaddingLeft(10);
            table.addCell(firstCell);
            table.addCell(second);
            table.addCell(thirdCell);


            PdfPTable transportChecks = new PdfPTable(2);
            transportChecks.getDefaultCell().setBorder(0);
            first = new Paragraph();
            second = new Paragraph();
            chunk = new Chunk("Food Grade:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk(trip.isFoodGrade() ? "Yes" : "No", normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setBorder(0);
            firstCell.setPaddingLeft(10);
            chunk = new Chunk("Weightment:  \n\t", normalBoldGray);
            second.add(chunk);
            chunk = new Chunk(trip.isWeightment() ? "Yes" : "No", normal);
            second.add(chunk);
            secondCell = new PdfPCell(second);
            secondCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            transportChecks.addCell(firstCell);
            transportChecks.addCell(secondCell);

            first = new Paragraph();
            second = new Paragraph();

            chunk = new Chunk("Heavy Duty:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk(trip.isHeavyDuty() ? "Yes" : "No", normal);

            first.add(chunk);
            //firstCell = new PdfPCell(first);
            firstCell = new PdfPCell(first);
            firstCell.setPaddingLeft(10);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);
            chunk = new Chunk("Ground:  \n\t", normalBoldGray);
            second.add(chunk);
            chunk = new Chunk(trip.isGround() ? "Yes" : "No", normal);
            second.add(chunk);
            secondCell = new PdfPCell(second);
            secondCell.setBorder(0);
            transportChecks.addCell(firstCell);
            transportChecks.addCell(secondCell);

            first = new Paragraph();
            second = new Paragraph();
            chunk = new Chunk("Dangerous Good:  \n\t", normalBoldGray);
            first.add(chunk);
            chunk = new Chunk(trip.isDangerousGoods() ? "Yes" : "No", normal);
            first.add(chunk);
            firstCell = new PdfPCell(first);
            firstCell.setPaddingLeft(10);
            firstCell.setBorder(0);
            //set border left color
            firstCell.setUseVariableBorders(true);
            firstCell.setBorderWidthLeft(1);
            firstCell.setBorderColorLeft(lightBlue);

            second.add(new Paragraph(""));
            secondCell = new PdfPCell(second);
            secondCell.setBorder(0);
            transportChecks.addCell(firstCell);
            transportChecks.addCell(secondCell);
            firstCell = new PdfPCell(transportChecks);
            firstCell.setBorder(0);

            //firstCell.setBackgroundColor(lightGray);
            PdfPTable notes = new PdfPTable(1);
            second = new Paragraph("");// = new Paragraph("Shipment Number: QAF-18001015",normal);
            chunk = new Chunk("Dangerous Notes:  ", normalBoldGray);
            second.add(chunk);
            chunk = new Chunk("\n" + trip.getDangerousNotes(), normal);
            second.add(chunk);
            PdfPCell singleColumnCell = new PdfPCell(second);
            singleColumnCell.setBorder(0);
            notes.addCell(singleColumnCell);
            secondCell = new PdfPCell(notes);
            //secondCell.setColspan(2);
            secondCell.setBorder(0);
            thirdCell = new PdfPCell();
            thirdCell.setBorder(0);
            third = new Paragraph("");
            thirdCell.addElement(third);
            thirdCell.setColspan(0);
            thirdCell.setBorder(0);
            //set border left color
            thirdCell.setUseVariableBorders(true);
            thirdCell.setBorderWidthLeft(1);
            thirdCell.setBorderColorLeft(lightBlue);
            table.addCell(firstCell);
            table.addCell(secondCell);
            table.addCell(thirdCell);


            return table;
        } else {
            PdfPTable table = new PdfPTable(1);
            table.addCell(new Paragraph("Incomplete Data"));
            return table;
        }

    }

    private PdfPTable getNotesAndSignatureSection() {
        PdfPTable table = new PdfPTable(1);
        table.getDefaultCell().setBorder(0);
        table.setWidthPercentage(100);

        Paragraph first = new Paragraph();
        PdfPCell firstCell;
        Chunk chunk = new Chunk("Notes/Comments:\n\n", largeBoldBlue);
        first.add(chunk);
        chunk = new Chunk(".................................................................................................................................................................." +
                "..................................................................\n\n" +
                ".................................................................................................................................................................." +
                "..................................................................\n ", normal);
        first.add(chunk);
        firstCell = new PdfPCell(first);
        firstCell.setBorder(0);
        table.addCell(firstCell);

        first = new Paragraph();
        chunk = new Chunk("Receiver Signature:\n\n\n\n", largeBoldBlue);
        first.add(chunk);
        chunk = new Chunk("................................\n ", normal);
        first.add(chunk);
        firstCell = new PdfPCell(first);
        firstCell.setBorder(0);
        //firstCell.setPaddingLeft(10);
        table.addCell(firstCell);

        first = new Paragraph();
        chunk = new Chunk("Name/Designation/Mobile Number:\n\n", largeBoldBlue);
        first.add(chunk);
        chunk = new Chunk(".................................................................................................................................................................." +
                "..................................................................\n ", normal);
        first.add(chunk);
        firstCell = new PdfPCell(first);
        firstCell.setBorder(0);
        //firstCell.setPaddingLeft(10);
        table.addCell(firstCell);

        return table;
    }

    private PdfPTable getLetterhead() throws IOException, DocumentException {

        Image img;

        if (company_name.equals("Al qafila")) {
            img = Image.getInstance("assets/images/alqafila-logo-01.png");

        } else {
            img = Image.getInstance("assets/images/Qafila-logo-01.png");

        }

        //  Image img= Image.getInstance("assets/images/alqafila-logo-01.png");
        img.scaleToFit(160f, 125f);
        PdfPTable table = new PdfPTable(2);
        return table;

    }


    private static void addEmptyLine(Paragraph paragraph, int number) {
        for (int i = 0; i < number; i++) {
            paragraph.add(new Paragraph(" "));
        }
    }

    /*For now this table has 2 columns. When converted to generic , pdf table will also be passed, aong with column numbers*/
    private PdfPTable addEmptyLineWithBorder(int number) throws DocumentException {
        PdfPTable table = new PdfPTable(2);
        table.getDefaultCell().setBorder(0);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2, 1});

        for (int i = 0; i < number; i++) {
            Paragraph paragraph1 = new Paragraph(" ");
            PdfPCell pdfPCell = new PdfPCell(paragraph1);
            //set border left color
            pdfPCell.setUseVariableBorders(true);
            pdfPCell.setBorder(0);
            pdfPCell.setBorderWidthLeft(1);
            pdfPCell.setBorderColorLeft(lightBlue);
            PdfPCell pdfPCell1 = new PdfPCell(new Paragraph(" "));
            pdfPCell1.setBorder(0);
            pdfPCell1.setBorderWidthLeft(1);
            pdfPCell1.setBorderColorLeft(lightBlue);

            table.addCell(pdfPCell);
            table.addCell(pdfPCell1);
        }
        return table;
    }

    public FileResponse getTripPDFDocument(Trip trip) {
        FileResponse fileResponse = new FileResponse();
        //TODO: requirement changed
        //Trip trip1 = tripRepository.findByTransport_request_idAndTruck_idAndDriver_id(trip.getTransport_request_id(),trip.getTruck_id(),trip.getDriver_id());
        Trip trip1 = tripRepository.findOne(trip.getId());

        try {
            S3ObjectInputStream s3Str = s3Client.getObject(bucketName, trip.getS3Key()).getObjectContent();

            fileResponse.setContent(IOUtils.toByteArray(s3Str));
        } catch (Exception e) {
            LOGGER.error("Error building content", e);
        }/*finally{}*/

        //fileResponse.setContentType(trip1.getContentType());
        return fileResponse;
    }


    
}

/*V1
 *
 * ByteArrayDataSource modified to mail.util.ByteArrayDataSource
 *
 * */