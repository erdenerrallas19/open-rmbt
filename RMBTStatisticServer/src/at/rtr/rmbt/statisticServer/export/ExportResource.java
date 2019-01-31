/*******************************************************************************
 * Copyright 2013-2015 alladin-IT GmbH
 * Copyright 2013-2015 Rundfunk und Telekom Regulierungs-GmbH (RTR-GmbH)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package at.rtr.rmbt.statisticServer.export;

import at.rtr.rmbt.statisticServer.ServerResource;
import at.rtr.rmbt.statisticServer.opendata.dto.OpenTestExportDTO;
import at.rtr.rmbt.statisticServer.opendata.dto.OpenTestSearchDTO;
import com.fasterxml.jackson.databind.PropertyNamingStrategy;
import com.fasterxml.jackson.databind.SequenceWriter;
import com.fasterxml.jackson.dataformat.csv.CsvGenerator;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.github.sett4.dataformat.xlsx.XlsxMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiImplicitParams;
import io.swagger.annotations.ApiOperation;
import org.apache.commons.dbutils.BasicRowProcessor;
import org.apache.commons.dbutils.GenerousBeanProcessor;
import org.apache.commons.dbutils.handlers.BeanListHandler;
import org.apache.commons.io.IOUtils;
import org.restlet.data.Disposition;
import org.restlet.data.MediaType;
import org.restlet.representation.OutputRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Api(value="/export")
public class ExportResource extends ServerResource
{
    private static final String FILENAME_CSV_HOURS = "netztest-opendata_hours-%HOURS%.csv";
    private static final String FILENAME_ZIP_HOURS = "netztest-opendata_hours-%HOURS%.zip";
    private static final String FILENAME_XLSX_HOURS = "netztest-opendata_hours-%HOURS%.zip";
    private static final String FILENAME_CSV = "netztest-opendata-%YEAR%-%MONTH%.csv";
    private static final String FILENAME_XLSX = "netztest-opendata-%YEAR%-%MONTH%.xlsx";
    private static final String FILENAME_ZIP = "netztest-opendata-%YEAR%-%MONTH%.zip";
    private static final String FILENAME_CSV_CURRENT = "netztest-opendata.csv";
    private static final String FILENAME_ZIP_CURRENT = "netztest-opendata.zip";
    private static final String FILENAME_XLSX_CURRENT = "netztest-opendata.xlsx";

    private static final boolean zip = true;
    
    private static long cacheThresholdMs;

    @Get
    @GET
    @Path("/export/netztest-opendata-{year}-{month}.{format}")
    @ApiOperation(httpMethod = "GET",
            value = "Export open data as CSV or XLSX",
            notes = "Bulk export open data entries",
            response = OpenTestExportDTO.class,
            produces = "text/csv",
            nickname = "export")
    @ApiImplicitParams({
            @ApiImplicitParam(name = "year", value = "Mandatory. The year that should be exported.", dataType = "string", example = "2017", paramType = "path", required = true),
            @ApiImplicitParam(name = "month", value = "Mandatory. The year that should be exported.", dataType = "integer", example = "0", paramType = "path", required = true),
            @ApiImplicitParam(name = "format", value = "Mandatory. Either ZIP (CSV) or XLSX.", dataType = "string", example = "xlsx", paramType = "path", required = true)
    })
    public Representation request(final String entity)
    {
        //Before doing anything => check if a cached file already exists and is new enough
        String property = System.getProperty("java.io.tmpdir");
        
    	final String filename_zip;
    	final String filename_csv;
    	final String filename_xlsx;
    	
        //allow filtering by month/year
        int year = -1;
        int month = -1;
        int hours = -1;
        boolean hoursExport = false;
        boolean dateExport = false;

        String tFormat = "csv";
        if (getRequest().getAttributes().containsKey("format")) {
            tFormat = getRequest().getAttributes().get("format").toString();
        }
        final boolean xlsx = tFormat.contains("xlsx");
        
        if (getRequest().getAttributes().containsKey("hours")) { // export by hours
        	try {
        		hours= Integer.parseInt(getRequest().getAttributes().get("hours").toString());
        	} catch (NumberFormatException ex) {
        		//Nothing -> just fall back
        	}
        	if (hours <= 7*24 && hours >= 1) {  //limit to 1 week (avoid DoS)
        		hoursExport = true;
        	}
        } 
        else if (!hoursExport && getRequest().getAttributes().containsKey("year")) {  // export by month/year 
        	try {
        		year= Integer.parseInt(getRequest().getAttributes().get("year").toString());
        		month = Integer.parseInt(getRequest().getAttributes().get("month").toString());
        	} catch (NumberFormatException ex) {
        		//Nothing -> just fall back
        	}
        	if (year < 2099 && month > 0 && month <= 12 && year > 2000) {
        		dateExport = true;
        	} 
        } 
        
        if (hoursExport) {
        	filename_zip = FILENAME_ZIP_HOURS.replace("%HOURS%", String.format("%03d",hours));
        	filename_csv = FILENAME_CSV_HOURS.replace("%HOURS%", String.format("%03d",hours));
        	filename_xlsx = FILENAME_XLSX_HOURS.replace("%HOURS%", String.format("%03d",hours));
        	cacheThresholdMs = 5*60*1000; //5 minutes
        } else if (dateExport) {
        	filename_zip = FILENAME_ZIP.replace("%YEAR%", Integer.toString(year)).replace("%MONTH%",String.format("%02d",month));
        	filename_csv = FILENAME_CSV.replace("%YEAR%", Integer.toString(year)).replace("%MONTH%",String.format("%02d",month));
        	filename_xlsx = FILENAME_XLSX.replace("%YEAR%", Integer.toString(year)).replace("%MONTH%",String.format("%02d",month));
        	cacheThresholdMs  = 23*60*60*1000; //23 hours
        } else {	
        	filename_zip = FILENAME_ZIP_CURRENT;
        	filename_csv = FILENAME_CSV_CURRENT;
        	filename_xlsx = FILENAME_XLSX_CURRENT;
        	cacheThresholdMs = 3*60*60*1000; //3 hours
        }
        final String filename = ((xlsx)?filename_xlsx:(zip)?filename_zip:filename_csv);

        final File cachedFile = new File(property + File.separator + filename);
        final File generatingFile = new File(property + File.separator + filename + "_tmp");
        if (cachedFile.exists()) {
            
            //check if file has been recently created OR a file is currently being created
            if (((cachedFile.lastModified() + cacheThresholdMs) > (new Date()).getTime()) ||
            		(generatingFile.exists() && (generatingFile.lastModified() + cacheThresholdMs) > (new Date()).getTime())) {

                //if so, return the cached file instead of a cost-intensive new one
                final OutputRepresentation result = new OutputRepresentation(xlsx ? MediaType.APPLICATION_MSOFFICE_XLSX : zip ? MediaType.APPLICATION_ZIP
                : MediaType.TEXT_CSV) {

                    @Override
                    public void write(OutputStream out) throws IOException {
                        InputStream is = new FileInputStream(cachedFile);
                        IOUtils.copy(is, out);
                        out.close();
                    }
                    
                };
                if (xlsx || zip) {
                    final Disposition disposition = new Disposition(Disposition.TYPE_ATTACHMENT);
                    disposition.setFilename(filename);
                    result.setDisposition(disposition);
                }
                return result;
        
            }
        }
        
        final String timeClause;
        
        if (dateExport)
        	timeClause = " AND (EXTRACT (month FROM t.time AT TIME ZONE 'UTC') = " + month + 
        	") AND (EXTRACT (year FROM t.time AT TIME ZONE 'UTC') = " + year + ") ";
        else if (hoursExport)
        	timeClause = " AND time > now() - interval '" + hours + " hours' ";
        else 
        	timeClause = " AND time > current_date - interval '31 days' ";
         
        
        final String sql = "SELECT" +
                " ('P' || t.open_uuid) open_uuid," +
                " ('O' || t.open_test_uuid) open_test_uuid," + 
                " to_char(t.time AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS') \"time\"," +
                " nt.group_name cat_technology," +
                " nt.name network_type," +
                " (CASE WHEN (t.geo_accuracy < ?) AND (t.geo_provider IS DISTINCT FROM 'manual') AND (t.geo_provider IS DISTINCT FROM 'geocoder') THEN" +
                " t.geo_lat" +
                " WHEN (t.geo_accuracy < ?) THEN" +
                " ROUND(t.geo_lat*1111)/1111" +
                " ELSE null" +
                " END) latitude," +
                " (CASE WHEN (t.geo_accuracy < ?) AND (t.geo_provider IS DISTINCT FROM 'manual') AND (t.geo_provider IS DISTINCT FROM 'geocoder') THEN" +
                " t.geo_long" +
                " WHEN (t.geo_accuracy < ?) THEN" +
                " ROUND(t.geo_long*741)/741 " +
                " ELSE null" +
                " END) longitude," +
                " (CASE WHEN ((t.geo_provider = 'manual') OR (t.geo_provider = 'geocoder')) THEN" +
                " 'rastered'" + //make raster transparent
                " ELSE t.geo_provider" +
                " END) loc_src," + 
                " (CASE WHEN (t.geo_accuracy < ?) AND (t.geo_provider IS DISTINCT FROM 'manual') AND (t.geo_provider IS DISTINCT FROM 'geocoder') " +
                " THEN round(t.geo_accuracy::float * 10)/10 " +
                " WHEN (t.geo_accuracy < 100) AND ((t.geo_provider = 'manual') OR (t.geo_provider = 'geocoder')) THEN 100" + // limit accuracy to 100m
                " WHEN (t.geo_accuracy < ?) THEN round(t.geo_accuracy::float * 10)/10" +
                " ELSE null END) loc_accuracy, " +
                " t.gkz_bev gkz," +
                " NULL zip_code," +
                " t.country_location country_location," + 
                " t.speed_download download_kbit," +
                " t.speed_upload upload_kbit," +
                " round(t.ping_median::float / 100000)/10 ping_ms," +
                " t.lte_rsrp," +
                " t.lte_rsrq," +
                " ts.name server_name," +
                " duration test_duration," +
                " num_threads," +
                " t.plattform platform," +
                " COALESCE(adm.fullname, t.model) model," +
                " client_software_version client_version," +
                " network_operator network_mcc_mnc," +
                " network_operator_name network_name," +
                " network_sim_operator sim_mcc_mnc," +
                " nat_type," +
                " public_ip_asn asn," +
                " client_public_ip_anonymized ip_anonym," +
                " (ndt.s2cspd*1000)::int ndt_download_kbit," +
                " (ndt.c2sspd*1000)::int ndt_upload_kbit," +
                " COALESCE(t.implausible, false) implausible," +
                " t.signal_strength," +
                " t.pinned pinned," +
                " t.kg_nr_bev kg_nr," +
                " t.gkz_sa gkz_sa," +
                " t.land_cover, " +
                " t.cell_location_id cell_area_code," +
                " t.cell_area_code cell_location_id," +
                " t.channel_number channel_number," +
                " t.radio_band radio_band" +
                " FROM test t" +
                /* no comma at then end !! */
                " LEFT JOIN network_type nt ON nt.uid=t.network_type" +
                " LEFT JOIN device_map adm ON adm.codename=t.model" +
                " LEFT JOIN test_server ts ON ts.uid=t.server_id" +
                " LEFT JOIN test_ndt ndt ON t.uid=ndt.test_id" +
                " WHERE " +
                " t.deleted = false" + 
                timeClause +
                " AND status = 'FINISHED'" +
                " ORDER BY t.uid";
        
        final String[] columns;
        final List<String[]> data = new ArrayList<>();
        PreparedStatement ps = null;
        ResultSet rs = null;

        final List<OpenTestExportDTO> results;
        try
        {
            ps = conn.prepareStatement(sql);
            
            //insert filter for accuracy
            double accuracy = Double.parseDouble(settings.getString("RMBT_GEO_ACCURACY_DETAIL_LIMIT"));
            ps.setDouble(1, accuracy);
            ps.setDouble(2, accuracy);
            ps.setDouble(3, accuracy);
            ps.setDouble(4, accuracy);
            ps.setDouble(5, accuracy);
            ps.setDouble(6, accuracy);
            
            if (!ps.execute())
                return null;
            rs = ps.getResultSet();


            BeanListHandler<OpenTestExportDTO> handler = new BeanListHandler<>(OpenTestExportDTO.class,new BasicRowProcessor(new GenerousBeanProcessor()));
            results = handler.handle(rs);

        }
        catch (final SQLException e)
        {
            e.printStackTrace();
            return null;
        }
        finally
        {
            try
            {
                if (rs != null)
                    rs.close();
                if (ps != null)
                    ps.close();
            }
            catch (final SQLException e)
            {
                e.printStackTrace();
            }
        }
        
        final OutputRepresentation result = new OutputRepresentation(xlsx ? MediaType.APPLICATION_MSOFFICE_XLSX : zip ? MediaType.APPLICATION_ZIP
                : MediaType.TEXT_CSV)
        {
            @Override
            public void write(OutputStream out) throws IOException
            {
                //cache in file => create temporary temporary file (to 
                // handle errors while fulfilling a request)
                String property = System.getProperty("java.io.tmpdir");
                final File cachedFile = new File(property + File.separator + filename + "_tmp");
                OutputStream outf = new FileOutputStream(cachedFile);
                
                if (zip && !xlsx)
                {
                    final ZipOutputStream zos = new ZipOutputStream(outf);
                    final ZipEntry zeLicense = new ZipEntry("LIZENZ.txt");
                    zos.putNextEntry(zeLicense);
                    final InputStream licenseIS = getClass().getResourceAsStream("DATA_LICENSE.txt");
                    IOUtils.copy(licenseIS, zos);
                    licenseIS.close();
                    
                    final ZipEntry zeCsv = new ZipEntry(filename_csv);
                    zos.putNextEntry(zeCsv);
                    outf = zos;
                }
                
                final OutputStreamWriter osw = new OutputStreamWriter(outf);



                if (xlsx) {
                    XlsxMapper mapper = new XlsxMapper();
                    mapper.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
                    CsvSchema schema = mapper.schemaFor(OpenTestExportDTO.class).withHeader();
                    SequenceWriter sequenceWriter = mapper.writer(schema).writeValues(outf);
                    sequenceWriter.writeAll(results);
                    sequenceWriter.flush();
                    sequenceWriter.close();
                }
                else {
                    final CsvMapper cm = new CsvMapper();
                    final CsvSchema schema;
                    cm.setPropertyNamingStrategy(PropertyNamingStrategy.SNAKE_CASE);
                    cm.enable(CsvGenerator.Feature.STRICT_CHECK_FOR_QUOTING);
                    schema = CsvSchema.builder().setLineSeparator("\r\n").setUseHeader(true)
                            .addColumnsFrom(cm.schemaFor(OpenTestExportDTO.class)).build();
                    cm.writer(schema).writeValue(outf, results);
                }
                
                if (zip)
                    outf.close();
                
                //if we reach this code, the data is now cached in a temporary tmp-file
                //so, rename the file for "production use2
                //concurrency issues should be solved by the operating system
                File newCacheFile = new File(property + File.separator + filename);
                Files.move(cachedFile.toPath(), newCacheFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                
                FileInputStream fis = new FileInputStream(newCacheFile);
                IOUtils.copy(fis, out);
                fis.close();
                out.close();
            }
        };
        if (xlsx || zip) {
            final Disposition disposition = new Disposition(Disposition.TYPE_ATTACHMENT);
            disposition.setFilename(filename);
            result.setDisposition(disposition);
        }
        
        return result;
    }
}
