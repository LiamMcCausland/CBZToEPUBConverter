import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.xml.parsers.*;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;

public class CBZToEPUBConverter extends JFrame {

    private JTextField filePathField;
    private JTextField yResolutionField; // Height field first
    private JTextField xResolutionField; // Width field second
    private JButton selectFileButton;
    private JButton convertButton;
    private File cbzFile;

    public CBZToEPUBConverter() {
        setTitle("CBZ to EPUB Converter");
        setSize(800, 800);
        setLayout(new BorderLayout());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        JPanel panel = new JPanel(new GridLayout(4, 1));

        // File selection section
        JPanel filePanel = new JPanel();
        filePathField = new JTextField(20);
        filePathField.setEditable(false);
        selectFileButton = new JButton("Select CBZ File");
        selectFileButton.addActionListener(new SelectFileAction());
        filePanel.add(filePathField);
        filePanel.add(selectFileButton);
        panel.add(filePanel);

        // Resolution input section
        JPanel resolutionPanel = new JPanel();
        resolutionPanel.add(new JLabel("Resolution (Height x Width): "));
        yResolutionField = new JTextField("800", 5); // Height field
        xResolutionField = new JTextField("800", 5); // Width field
        resolutionPanel.add(new JLabel("Height: "));
        resolutionPanel.add(yResolutionField);
        resolutionPanel.add(new JLabel("Width: "));
        resolutionPanel.add(xResolutionField);
        panel.add(resolutionPanel);

        // Conversion button
        convertButton = new JButton("Convert to EPUB");
        convertButton.addActionListener(new ConvertAction());
        panel.add(convertButton);

        add(panel, BorderLayout.CENTER);
        setVisible(true);
    }

    private class SelectFileAction implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Select a CBZ file to convert to EPUB");
            fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("CBZ files", "cbz"));

            int userSelection = fileChooser.showOpenDialog(null);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                cbzFile = fileChooser.getSelectedFile();
                filePathField.setText(cbzFile.getAbsolutePath());
            }
        }
    }

    private class ConvertAction implements ActionListener {
        public void actionPerformed(ActionEvent e) {
            if (cbzFile == null) {
                JOptionPane.showMessageDialog(null, "Please select a CBZ file.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            try {
                int yResolution = Integer.parseInt(yResolutionField.getText().trim());
                int xResolution = Integer.parseInt(xResolutionField.getText().trim());

                // Check that height is larger than width
                if (yResolution <= xResolution) {
                    JOptionPane.showMessageDialog(null, "Height must be greater than Width.", "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                String epubPath = cbzFile.getAbsolutePath().replace(".cbz", ".epub");
                convertCBZToEPUB(cbzFile.getAbsolutePath(), epubPath, xResolution, yResolution);
                JOptionPane.showMessageDialog(null, "Conversion complete!\nSaved as: " + epubPath);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(null, "Please enter valid resolutions.", "Error", JOptionPane.ERROR_MESSAGE);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(null, "An error occurred: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
public static void convertCBZToEPUB(String cbzPath, String epubPath, int xResolution, int yResolution) throws Exception {
        File tempDir = Files.createTempDirectory("cbz_images").toFile();
        unzip(cbzPath, tempDir);

        try (ZipOutputStream epubZip = new ZipOutputStream(new FileOutputStream(epubPath))) {
            addMimetype(epubZip);
            addContainer(epubZip);
            addImagesAndPages(tempDir, epubZip, xResolution, yResolution);
            addContentOpf(epubZip, tempDir);
        }

        deleteDirectory(tempDir);
    }

    private static void unzip(String zipFilePath, File destDir) throws IOException {
        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                File filePath = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    filePath.mkdirs();
                } else {
                    filePath.getParentFile().mkdirs();
                    try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath))) {
                        byte[] bytesIn = new byte[4096];
                        int read;
                        while ((read = zipIn.read(bytesIn)) != -1) {
                            bos.write(bytesIn, 0, read);
                        }
                    }
                }
                zipIn.closeEntry();
            }
        }
    }

    private static void addMimetype(ZipOutputStream epubZip) throws IOException {
        ZipEntry mimetypeEntry = new ZipEntry("mimetype");
        epubZip.putNextEntry(mimetypeEntry);
        epubZip.write("application/epub+zip".getBytes());
        epubZip.closeEntry();
    }

    private static void addContainer(ZipOutputStream epubZip) throws IOException {
        epubZip.putNextEntry(new ZipEntry("META-INF/container.xml"));
        String containerContent = "<?xml version=\"1.0\"?>\n" +
                "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">\n" +
                "    <rootfiles>\n" +
                "        <rootfile full-path=\"OPS/content.opf\" media-type=\"application/oebps-package+xml\"/>\n" +
                "    </rootfiles>\n" +
                "</container>";
        epubZip.write(containerContent.getBytes());
        epubZip.closeEntry();
    }

    private static void addImagesAndPages(File imageDir, ZipOutputStream epubZip, int xResolution, int yResolution) throws IOException {
        File[] images = imageDir.listFiles();
        if (images == null) return;

        for (File image : images) {
            if (image.isFile()) {
                BufferedImage originalImage = ImageIO.read(image);
                BufferedImage resizedImage = resizeImage(originalImage, xResolution, yResolution);

                String imagePath = "OPS/images/" + image.getName();
                epubZip.putNextEntry(new ZipEntry(imagePath));
                ImageIO.write(resizedImage, "jpeg", epubZip); // Save as JPEG in EPUB
                epubZip.closeEntry();

                String xhtmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                        "<!DOCTYPE html>\n" +
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n" +
                        "<head><title>Image Page</title></head>\n" +
                        "<body><img src=\"../images/" + image.getName() + "\" alt=\"\"/></body>\n" +
                        "</html>";

                String xhtmlPath = "OPS/pages/" + image.getName().replaceFirst("[.][^.]+$", "") + ".xhtml";
                epubZip.putNextEntry(new ZipEntry(xhtmlPath));
                epubZip.write(xhtmlContent.getBytes());
                epubZip.closeEntry();
            }
        }
    }

    private static BufferedImage resizeImage(BufferedImage originalImage, int width, int height) {
        BufferedImage resizedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resizedImage.createGraphics();
        g.drawImage(originalImage, 0, 0, width, height, null);
        g.dispose();
        return resizedImage;
    }

    private static void addContentOpf(ZipOutputStream epubZip, File tempDir) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.newDocument();

        // Create root element <package>
        Element packageElement = doc.createElement("package");
        packageElement.setAttribute("version", "3.0");
        packageElement.setAttribute("xmlns", "http://www.idpf.org/2007/opf");
        doc.appendChild(packageElement);

        // Create <metadata> element
        Element metadata = doc.createElement("metadata");
        packageElement.appendChild(metadata);

        Element title = doc.createElement("title");
        title.setTextContent("Converted CBZ");
        metadata.appendChild(title);

        Element creator = doc.createElement("creator");
        creator.setTextContent("Your Name");
        metadata.appendChild(creator);

        // Create <manifest> element
        Element manifest = doc.createElement("manifest");
        packageElement.appendChild(manifest);

        // Create <spine> element
        Element spine = doc.createElement("spine");
        packageElement.appendChild(spine);

        File[] images = tempDir.listFiles();
        if (images != null) {
            for (File image : images) {
                if (image.isFile()) {
                    String imageName = image.getName();
                    String imageId = imageName.replaceFirst("[.][^.]+$", "");

                    Element item = doc.createElement("item");
                    item.setAttribute("id", imageId);
                    item.setAttribute("href", "pages/" + imageId + ".xhtml");
                    item.setAttribute("media-type", "application/xhtml+xml");
                    manifest.appendChild(item);

                    Element itemref = doc.createElement("itemref");
                    itemref.setAttribute("idref", imageId);
                    spine.appendChild(itemref);
                }
            }
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        DOMSource source = new DOMSource(doc);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        transformer.transform(source, new StreamResult(baos));

        epubZip.putNextEntry(new ZipEntry("OPS/content.opf"));
        epubZip.write(baos.toByteArray());
        epubZip.closeEntry();
    }

    private static void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                deleteDirectory(file);
            }
        }
        dir.delete();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(CBZToEPUBConverter::new);
    }
}

