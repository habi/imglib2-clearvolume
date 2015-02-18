/**
 *
 */
package de.mpicbg.jug.plugins;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Image;
import java.net.URL;

import javax.swing.ImageIcon;
import javax.swing.JFrame;
import javax.swing.JOptionPane;

import net.imagej.Dataset;
import net.imagej.ImgPlus;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;

import org.scijava.command.Command;
import org.scijava.plugin.Menu;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;

import clearvolume.utils.AppleMac;
import de.mpicbg.jug.clearvolume.gui.ClearVolumeSplashFrame;
import de.mpicbg.jug.clearvolume.gui.GenericClearVolumeGui;

/**
 * @author jug
 */
@Plugin( menu = { @Menu( label = "Plugins" ),
				 @Menu( label = "ClearVolume" ),
				 @Menu( label = "Open in ClearVolume" ) }, description = "Opens Stack in ClearVolume.", headless = false, type = Command.class )
public class ClearVolumePlugin< T extends RealType< T > & NativeType< T >> implements Command {

	@Parameter( label = "3D ImgPlus to be shown." )
	private Dataset dataset;
	private ImgPlus< T > imgPlus;

	private final int windowWidth = 1200;
	private final int windowHeight = 900;

	@Parameter( label = "Max texture size", min = "16", max = "1600", stepSize = "100", columns = 5, description = "Max texture resolution (per axis)." )
	private int textureResolution = 768;

	@Parameter( label = "try using CUDA if supported" )
	private boolean useCuda = false;

	private JFrame frame = null;
	private GenericClearVolumeGui< T > panelGui = null;

	/**
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		imgPlus = ( ImgPlus< T > ) dataset.getImgPlus();

		final boolean isShowable = checkIfShowable( imgPlus, true );
		useCuda = !( !useCuda ); // to avoid eclipse making this field 'final' -- stupid!

		if ( isShowable ) {
			final Dimension screenDims = java.awt.Toolkit.getDefaultToolkit().getScreenSize();

			textureResolution = Math.min( textureResolution, screenDims.width );

			frame = new JFrame( "ClearVolume" );
			frame.setLayout( new BorderLayout() );
			frame.setBounds( ( screenDims.width - windowWidth ) / 2, ( screenDims.height - windowHeight ) / 2, windowWidth, windowHeight );

			final Image finalicon = GenericClearVolumeGui.getCurrentAppIcon();

			final ClearVolumeSplashFrame loadFrame = new ClearVolumeSplashFrame();

			panelGui =
					new GenericClearVolumeGui< T >( imgPlus, textureResolution, useCuda );
			frame.add( panelGui );
			setClearVolumeIcon( frame );
			frame.setVisible( true );
			frame.revalidate();

			loadFrame.dispose();

			GenericClearVolumeGui.setCurrentAppIcon( finalicon );
		}
	}

	/**
	 * Checks if a given image has an compatible format to be shown.
	 *
	 * @param imgPlus2
	 * @return true, if image is of supported type and structure.
	 */
	private boolean checkIfShowable( final ImgPlus< T > imgPlus2, final boolean showErrorDialogs ) {
		boolean ret = true;
		String message = "";
		if ( imgPlus.numDimensions() < 3 || imgPlus.numDimensions() > 4 ) {
			message =
					"Only images with 3 (X,Y,Z) or 4 (X,Y,C,Z) dimensions\ncan be shown, current image has " + imgPlus.numDimensions() + " dimensions.";
			ret = false;
		}

		if ( !message.equals( "" ) ) {
			JOptionPane.showMessageDialog( frame, message, "Image Format Error", JOptionPane.ERROR_MESSAGE );
		}

		return ret;
	}

	private void setClearVolumeIcon( final JFrame frame ) {
		try
		{
			final URL lImageURL =
					getClass().getResource( "/clearvolume/icon/ClearVolumeIcon256.png" );
			final ImageIcon lImageIcon = new ImageIcon( lImageURL );

			if ( AppleMac.isMac() )
			{
				AppleMac.setApplicationIcon( lImageIcon.getImage() );
				AppleMac.setApplicationName( "ClearVolume" );
			}

			frame.setIconImage( lImageIcon.getImage() );
		} catch ( final Throwable e ) {
			e.printStackTrace();
		}

	}

}
