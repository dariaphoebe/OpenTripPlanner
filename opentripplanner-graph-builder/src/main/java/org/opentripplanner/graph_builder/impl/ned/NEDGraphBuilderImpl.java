/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.graph_builder.impl.ned;

import java.awt.geom.Point2D;
import java.util.LinkedList;
import java.util.List;

import org.geotools.geometry.DirectPosition2D;
import org.opengis.coverage.Coverage;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.graph_builder.services.ned.NEDGridCoverageFactory;
import org.opentripplanner.routing.core.Edge;
import org.opentripplanner.routing.core.Graph;
import org.opentripplanner.routing.core.Vertex;
import org.opentripplanner.routing.edgetype.Street;

import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;

/**
 * {@link GraphBuilder} plugin that takes a constructed (@link Graph} and overlays it onto National
 * Elevation Dataset (NED) raster data, creating elevation profiles for each Street encountered in
 * the Graph. The elevation profile is stored as a {@link PackedCoordinateSequence}, where each
 * (x,y) pair represents one sample, with the x-coord representing the distance along the edge as,
 * measured from the start, and the y-coord representing the sampled elevation at that point (both
 * in meters).
 * 
 * @author demory
 * 
 */
public class NEDGraphBuilderImpl implements GraphBuilder {

    private NEDGridCoverageFactory gridCoverageFactory;

    private Coverage coverage;

    /**
     * The sampling frequency in meters. Defaults to 10m, the approximate resolution of 1/3 arc-second
     * NED data.
     */
    private double sampleFreqM = 10;

    /** the average latitude of the graph vertices; used for distance calculations */
    private double averageLatitude;

    public void setGridCoverageFactory(NEDGridCoverageFactory factory) {
        gridCoverageFactory = factory;
    }

    public void setSampleFrequency(double freq) {
        sampleFreqM = freq;
    }

    @Override
    public void buildGraph(Graph graph) {

        coverage = gridCoverageFactory.getGridCoverage();

        double total = 0, count = 0;

        for (Vertex vv : graph.getVertices()) {
            total += vv.getY();
            count++;
        }
        averageLatitude = total / count;

        count = 0;
        for (Vertex vv : graph.getVertices()) {
            for (Edge ee : vv.getOutgoing()) {
                if (ee instanceof Street) {
                    processEdge((Street) ee);
                }
            }
        }

    }

    /**
     * Processes a single {@link Street} edge, creating and assigning the elevation profile.
     * 
     * @param st the street edge
     */
    private void processEdge(Street st) {
        Geometry g = (Geometry) st.getGeometry();
        Coordinate[] coords = g.getCoordinates();

        double currentD = 0, totalD = 0;

        double eq = 2 * 6378137 * Math.PI;
        double oneDegLon = eq * Math.cos(Math.toRadians(averageLatitude)) / 360;
        double sampleFreqD = sampleFreqM / oneDegLon;

        DirectPosition2D position = new DirectPosition2D(coords[0].x, coords[0].y);

        List<Coordinate> coordList = new LinkedList<Coordinate>();
        try {

            // add the initial sample (x=0)
            coordList.add(new Coordinate(0, getElevation(position.x, position.y)));

            // main sample loop
            int freqCount = 0;
            for (int i = 0; i < coords.length - 1; i++) {

                double segLenD = Point2D.distance(coords[i].x, coords[i].y, coords[i + 1].x,
                        coords[i + 1].y);
                double lastD = currentD;
                currentD += segLenD;
                totalD += segLenD;
                int iterCount = 0;
                double distIntoSegD = sampleFreqD - lastD;
                double dx = coords[i + 1].x - coords[i].x;
                double dy = coords[i + 1].y - coords[i].y;
                while (currentD > sampleFreqD) {
                    freqCount++;
                    iterCount++;
                    double t = (distIntoSegD + iterCount * sampleFreqD) / segLenD;
                    position = new DirectPosition2D(coords[i].x + t * dx, coords[i].y + t * dy);
                    coordList.add(new Coordinate(sampleFreqM * freqCount, getElevation(position.x,
                            position.y)));
                    currentD -= sampleFreqD;
                }
            }

            // end sample (x=length)
            if (totalD % sampleFreqD != 0) {
                position = new DirectPosition2D(coords[coords.length - 1].x,
                        coords[coords.length - 1].y);
                double totalM = totalD * oneDegLon;
                coordList.add(new Coordinate(totalM, getElevation(position.x, position.y)));
            }

            Coordinate coordArr[] = new Coordinate[coordList.size()];
            PackedCoordinateSequence elevPCS = new PackedCoordinateSequence.Double(coordList
                    .toArray(coordArr));

//            for (Coordinate c : elevPCS.toCoordinateArray()) System.out.println(" "+c.toString());

            st.setElevationProfile(elevPCS);

        } catch (Exception ex) {
            throw new IllegalStateException("error processing edge in NED graph builder", ex);
        }

    }

    /**
     * Method for retreiving the elevation at a given coordinate.
     * 
     * @param x the query latitude (NAD83)
     * @param y the query longitude (NAD83)
     * @return elevation in meters
     */
    private double getElevation(double x, double y) {
        double values[] = new double[1];
        coverage.evaluate(new DirectPosition2D(x, y), values);
        return values[0];
    }

}