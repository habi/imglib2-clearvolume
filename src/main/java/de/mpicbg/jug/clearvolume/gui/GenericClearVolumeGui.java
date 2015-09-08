/**
 *
 */
package de.mpicbg.jug.clearvolume.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

import com.apple.eawt.Application;
import com.jogamp.newt.awt.NewtCanvasAWT;

import clearvolume.renderer.ControlJPanel;
import net.imagej.ImgPlus;
import net.imagej.axis.Axes;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.display.ColorTable;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.view.Views;
import net.miginfocom.swing.MigLayout;


/**
 * @author jug
 */
public class GenericClearVolumeGui< T extends RealType< T > & NativeType< T >> extends JPanel
		implements
		ActionListener,
		ActiveLayerListener,
		ChangeListener {

	public class LoopThread extends Thread {

		boolean doit = true;

		@Override
		public void run() {
			while ( doit ) {
				try {
					if ( fps == 0 )
						Thread.sleep( 5000 );
					else
						Thread.sleep( 1000 / fps );
					t++;
					if ( t > sliderTime.getMaximum() ) t = 0;
					sliderTime.setValue( t );
				} catch ( final InterruptedException e ) {
					e.printStackTrace();
				}
			}
		}

		public void endLooping() {
			doit = false;
		}

	}

	private Container ctnrClearVolume;
	private NewtCanvasAWT newtClearVolumeCanvas;
	private JPanel panelControls;

	private JButton buttonCredits;

	private JButton buttonResetView;
	private JButton buttonUpdateView;

	private JTextField txtVoxelSizeX;
	private JTextField txtVoxelSizeY;
	private JTextField txtVoxelSizeZ;

	private int t = -1;
	private JSlider sliderTime;
	private JLabel lblTime;
	private JButton buttonPlayTime;
	private boolean bDoRenormalize;
	private JCheckBox cbRenormalizeFrames;


	private JButton buttonToggleBox;
	private JButton buttonToggleRecording;
	private List< ChannelWidget > channelWidgets;
	ControlJPanel panelClearVolumeControl;

	private int maxTextureResolution;
	private boolean useCuda;

	private ImgPlus< T > imgPlus;
	private List< RandomAccessibleInterval< T >> images;
	private ClearVolumeManager< T > cvManager;
	private LoopThread threadLoopTime;
	private JLabel lblFps;
	private JTextField txtFps;
	private int fps = 5;

	/**
	 * The LUTs as they are received from the DatasetView.
	 * They are converted into ClearVolume TransferFunctions and set before
	 * rendering.
	 */
	private List< ColorTable > luts;

	public GenericClearVolumeGui( final ImgPlus< T > imgPlus ) {
		this( imgPlus, 768, true );
	}

	public GenericClearVolumeGui(
			final ImgPlus< T > imgPlus,
			final int textureResolution,
			final boolean useCuda ) {
		this( imgPlus, null, textureResolution, useCuda );
	}

	/**
	 * @param imgPlus2
	 * @param luts
	 * @param textureResolution
	 * @param useCuda
	 */
	public GenericClearVolumeGui(
			final ImgPlus< T > imgPlus2,
			final List< ColorTable > luts,
			final int textureResolution,
			final boolean useCuda ) {
		super( true );

		this.imgPlus = imgPlus;
		this.luts = luts;
		images = new ArrayList< RandomAccessibleInterval< T >>();
		setTextureSizeAndCudaFlag( textureResolution, useCuda );

		if ( imgPlus != null ) {
			setImagesFromImgPlus( imgPlus );
			launchClearVolumeManager();
		}
	}

	private void setImagesFromImgPlus( final ImgPlus< T > imgPlus ) {
		if ( imgPlus == null ) return;

		final int dC = imgPlus.dimensionIndex( Axes.CHANNEL );
		final int dT = imgPlus.dimensionIndex( Axes.TIME );

		if ( imgPlus.numDimensions() == 3 ) {
			images.add( imgPlus );
		} else if ( imgPlus.numDimensions() == 4 ) {
			if ( dC == -1 ) {
				if ( dT == -1 ) { throw new IllegalArgumentException( "Four dimensional input image without CHANNEL axis must contain a TIME axis! Neither of both found..." ); }
				t = 0;
				extractChannelsAtT( t );
			} else
				for ( int channel = 0; channel < imgPlus.dimension( dC ); channel++ ) {
				final RandomAccessibleInterval< T > rai = Views.hyperSlice( imgPlus, 2, channel );
				images.add( rai );
			}
		} else if ( imgPlus.numDimensions() == 5 ) {
			if ( dT == -1 ) { throw new IllegalArgumentException( "Five dimensional input image must contain a TIME axis!" ); }
			t = 0;
			extractChannelsAtT( t );
		}
	}

	public void launchClearVolumeManager() {
		// if cvManager is set from previous session - free everything!
		if ( cvManager != null ) {
			cvManager.close();
			this.closeOldSession();
		}

		// instantiate a NEW ClearVolumeManager
		try {
			final GenericClearVolumeGui< T > self = this;
			final Runnable todo = new Runnable() {

				@Override
				public void run() {
					cvManager =
							new ClearVolumeManager< T >( images, luts, maxTextureResolution, maxTextureResolution, useCuda );
					cvManager.addActiveLayerChangedListener( self );
				}
			};

			if ( javax.swing.SwingUtilities.isEventDispatchThread() ) {
				todo.run();
			} else {
				SwingUtilities.invokeAndWait( todo );
			}
		} catch ( final Exception e ) {
			System.err.println( "Launching CV session was interrupted in GenericClearVolumeGui!" );
			e.printStackTrace();
		}

		final int dX = imgPlus.dimensionIndex( Axes.X );
		final int dY = imgPlus.dimensionIndex( Axes.Y );
		final int dZ = imgPlus.dimensionIndex( Axes.Z );
		if ( dX != -1 && dY != -1 && dZ != -1 ) {
			cvManager.setVoxelSize(
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.X ) ),
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.Y ) ),
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.Z ) ) );
		} else if ( imgPlus.numDimensions() >= 3 ) {
			cvManager.setVoxelSize(
					imgPlus.averageScale( 0 ),
					imgPlus.averageScale( 1 ),
					imgPlus.averageScale( 2 ) );
		}
		cvManager.run();

		// Create necessary channel widgets!
		this.channelWidgets = new ArrayList< ChannelWidget >();
		for ( int i = 0; i < images.size(); i++ ) {
			channelWidgets.add( new ChannelWidget( cvManager, i ) );
		}

		buildGui();
	}

	public void relaunchClearVolumeManager( final ClearVolumeManager< T > oldManager ) {
		final double[] oldMinI = oldManager.getMinIntensities();
		final double[] oldMaxI = oldManager.getMaxIntensities();
		final List< RandomAccessibleInterval< T >> oldImages = oldManager.getChannelImages();
		final double oldVoxelSizeX = oldManager.getVoxelSizeX();
		final double oldVoxelSizeY = oldManager.getVoxelSizeY();
		final double oldVoxelSizeZ = oldManager.getVoxelSizeZ();

		oldManager.close();
		this.closeOldSession();

		// instantiate a NEW ClearVolumeManager using the old images and params
		try {
			final GenericClearVolumeGui< T > self = this;
			final Runnable todo = new Runnable() {

				@Override
				public void run() {
					cvManager =
							new ClearVolumeManager< T >( oldImages, luts, maxTextureResolution, maxTextureResolution, useCuda );
					cvManager.addActiveLayerChangedListener( self );
				}
			};

			if ( javax.swing.SwingUtilities.isEventDispatchThread() ) {
				todo.run();
			} else {
				SwingUtilities.invokeAndWait( todo );
			}
		} catch ( final Exception e ) {
			System.err.println( "Relaunching CV session was interrupted in GenericClearVolumeGui!" );
		}

		cvManager.setVoxelSize( oldVoxelSizeX, oldVoxelSizeY, oldVoxelSizeZ );
		for ( int i = 0; i < oldImages.size(); i++ ) {
			cvManager.setIntensityValues( i, oldMinI[ i ], oldMaxI[ i ] );
		}

		cvManager.run();
		buildGui();
	}

	private void setTextureSizeAndCudaFlag( final int textureRes, final boolean useCuda ) {
		this.maxTextureResolution = textureRes;
		this.useCuda = useCuda;

		if ( cvManager != null ) {
			cvManager.setTextureSize( textureRes, textureRes );
			cvManager.setCuda( true );
		}
	}

	public ClearVolumeManager< T > getClearVolumeManager() {
		return cvManager;
	}

	public void pushParamsToGui() {
		txtVoxelSizeX.setText( "" + cvManager.getVoxelSizeX() );
		txtVoxelSizeY.setText( "" + cvManager.getVoxelSizeY() );
		txtVoxelSizeZ.setText( "" + cvManager.getVoxelSizeZ() );
	}

	/**
	 * Read all validly entered text field values and activate them.
	 */
	private void activateGuiValues() {
		final int i;
		double d;

		try {
			d = Double.parseDouble( txtVoxelSizeX.getText() );
		} catch ( final NumberFormatException e ) {
			d = cvManager.getVoxelSizeX();
		}
		final double voxelSizeX = d;

		try {
			d = Double.parseDouble( txtVoxelSizeY.getText() );
		} catch ( final NumberFormatException e ) {
			d = cvManager.getVoxelSizeY();
		}
		final double voxelSizeY = d;

		try {
			d = Double.parseDouble( txtVoxelSizeZ.getText() );
		} catch ( final NumberFormatException e ) {
			d = cvManager.getVoxelSizeZ();
		}
		final double voxelSizeZ = d;

		cvManager.setVoxelSize( voxelSizeX, voxelSizeY, voxelSizeZ );
	}

	private void buildGui() {
//		this.setIgnoreRepaint( true );
		this.setVisible( false );
		this.removeAll();

		this.setLayout( new BorderLayout() );

		ctnrClearVolume = new Container();
		ctnrClearVolume.setLayout( new BorderLayout() );

		if ( cvManager != null ) {
			newtClearVolumeCanvas = cvManager.getClearVolumeRendererInterface().getNewtCanvasAWT();
			ctnrClearVolume.add( newtClearVolumeCanvas, BorderLayout.CENTER );

			panelClearVolumeControl =
					new ControlJPanel( cvManager.getActiveChannelIndex(), cvManager.getClearVolumeRendererInterface() );
			panelClearVolumeControl.setClearVolumeRendererInterface( cvManager.getClearVolumeRendererInterface() );
		} else {
			System.err.println( "ClearVolumeTableCellView: Did you intend this? You called buildGui while cvManager==null!" );
		}

		// Main controls panel
		// -------------------
		panelControls = new JPanel();
		panelControls.setLayout( new BoxLayout( panelControls, BoxLayout.Y_AXIS ) );
		panelControls.add( Box.createVerticalGlue() );

		// Credits baby!!!
		buttonCredits = new JButton( "Help + how to cite us!" );
		buttonCredits.setForeground( Color.darkGray );
		buttonCredits.addActionListener( this );

		final JPanel panelCreditsHelper = new JPanel( new GridLayout( 1, 1 ) );
		panelCreditsHelper.setBorder( BorderFactory.createEmptyBorder( 5, 5, 2, 2 ) );

		panelCreditsHelper.add( buttonCredits );

		JPanel shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( panelCreditsHelper, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
		panelControls.add( shrinkingHelper );

		// Parameters that require a view update
		// -------------------------------------
		JPanel panelControlsHelper = new JPanel( new GridLayout( 3, 2 ) );
		panelControlsHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );

		final JLabel lblVoxelSizeX = new JLabel( "VoxelDimension.X" );
		txtVoxelSizeX = new JTextField( 8 );
		txtVoxelSizeX.setActionCommand( "UpdateView" );
		txtVoxelSizeX.addActionListener( this );
		final JLabel lblVoxelSizeY = new JLabel( "VoxelDimension.Y" );
		txtVoxelSizeY = new JTextField( 8 );
		txtVoxelSizeY.setActionCommand( "UpdateView" );
		txtVoxelSizeY.addActionListener( this );
		final JLabel lblVoxelSizeZ = new JLabel( "VoxelDimension.Z" );
		txtVoxelSizeZ = new JTextField( 8 );
		txtVoxelSizeZ.setActionCommand( "UpdateView" );
		txtVoxelSizeZ.addActionListener( this );

		panelControlsHelper.add( lblVoxelSizeX );
		panelControlsHelper.add( txtVoxelSizeX );
		panelControlsHelper.add( lblVoxelSizeY );
		panelControlsHelper.add( txtVoxelSizeY );
		panelControlsHelper.add( lblVoxelSizeZ );
		panelControlsHelper.add( txtVoxelSizeZ );

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( panelControlsHelper, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
		panelControls.add( shrinkingHelper );

		buttonUpdateView = new JButton( "Set" );
		buttonUpdateView.addActionListener( this );

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( buttonUpdateView, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
		panelControls.add( shrinkingHelper );

		buttonResetView = new JButton( "Reset" );
		buttonResetView.addActionListener( this );

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( buttonResetView, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 22, 2 ) );
		panelControls.add( shrinkingHelper );

		// Toggle-buttons
		// --------------
		buttonToggleBox = new JButton( "Show/Unshow Box" );
		buttonToggleBox.addActionListener( this );

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( buttonToggleBox, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
		panelControls.add( shrinkingHelper );

		buttonToggleRecording = new JButton( "Start/Stop Recording" );
		buttonToggleRecording.addActionListener( this );

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( buttonToggleRecording, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 22, 2 ) );
		panelControls.add( shrinkingHelper );

		// Channel Widgets
		// ===============
		panelControlsHelper = new JPanel( new GridLayout( channelWidgets.size(), 1 ) );
		panelControlsHelper.setBorder( BorderFactory.createTitledBorder( "Channels" ) );

		for ( int i = 0; i < channelWidgets.size(); i++ ) {
			panelControlsHelper.add( channelWidgets.get( i ) );
		}
		channelWidgets.get( 0 ).addSelectionVisuals();

		shrinkingHelper = new JPanel( new BorderLayout() );
		shrinkingHelper.add( panelControlsHelper, BorderLayout.SOUTH );
		shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
		panelControls.add( shrinkingHelper );

		// Time related
		// ============
		if ( imgPlus.dimensionIndex( Axes.TIME ) != -1 ) {
			panelControlsHelper = new JPanel( new MigLayout() );
			panelControlsHelper.setBorder( BorderFactory.createTitledBorder( "Time" ) );

			lblTime = new JLabel( String.format( "t=%02d", ( t + 1 ) ) );
			lblFps = new JLabel( "fps:" );

			txtFps = new JTextField( 2 );
			txtFps.setText( "" + fps );
			txtFps.addActionListener( this );

			sliderTime =
					new JSlider( 0, ( int ) imgPlus.max( imgPlus.dimensionIndex( Axes.TIME ) ), 0 );
			sliderTime.addChangeListener( this );
			buttonPlayTime = new JButton();
			buttonPlayTime.addActionListener( this );
			setIcon( buttonPlayTime, "play.gif", ">", Color.BLUE );
			cbRenormalizeFrames = new JCheckBox( "normalize each time-point" );
			cbRenormalizeFrames.addActionListener( this );

			panelControlsHelper.add( lblTime );
			panelControlsHelper.add( buttonPlayTime );
			panelControlsHelper.add( sliderTime, "span, wrap" );
			panelControlsHelper.add( lblFps );
			panelControlsHelper.add( txtFps );
			panelControlsHelper.add( cbRenormalizeFrames );

			shrinkingHelper = new JPanel( new BorderLayout() );
			shrinkingHelper.add( panelControlsHelper, BorderLayout.SOUTH );
			shrinkingHelper.setBorder( BorderFactory.createEmptyBorder( 0, 5, 2, 2 ) );
			panelControls.add( shrinkingHelper );
		}

		// Display hijacked control container if possible
		// ----------------------------------------------
		if ( panelClearVolumeControl != null ) {
			this.add( panelClearVolumeControl, BorderLayout.SOUTH );
		}

		this.add( ctnrClearVolume, BorderLayout.CENTER );

		final JPanel helperPanel = new JPanel( new BorderLayout() );
		helperPanel.add( panelControls, BorderLayout.NORTH );

		final JScrollPane scrollPane = new JScrollPane( helperPanel );
		scrollPane.setHorizontalScrollBarPolicy( JScrollPane.HORIZONTAL_SCROLLBAR_NEVER );
		this.add( scrollPane, BorderLayout.EAST );

		// Update the values in the gui fields
		pushParamsToGui();

//		this.setIgnoreRepaint( false );
		this.setVisible( true );
	}

	/**
	 * Cleans up all ClearVolume resources and empties this panel.
	 */
	public void closeOldSession() {
		try {
			final GenericClearVolumeGui< T > self = this;
			final Runnable todo = new Runnable() {

				@Override
				public void run() {
					if ( newtClearVolumeCanvas != null )
						ctnrClearVolume.remove( newtClearVolumeCanvas );
					if ( cvManager != null ) cvManager.close();
					self.removeAll();
				}
			};

			if ( javax.swing.SwingUtilities.isEventDispatchThread() ) {
				todo.run();
			} else {
				SwingUtilities.invokeAndWait( todo );
			}
		} catch ( final Exception e ) {
			System.err.println( "Closing of an old CV session was interrupted in GenericClearVolumeGui!" );
		}
	}

	/**
	 * @see de.mpicbg.jug.clearvolume.gui.ActiveLayerListener#activeLayerChanged(int)
	 */
	@Override
	public void activeLayerChanged( final int layerId ) {
		int i = 0;
		for ( final ChannelWidget cw : channelWidgets ) {
			if ( i != layerId ) {
				cw.removeSelectionVisuals();
			}
			i++;
		}

		this.remove( panelClearVolumeControl );
		panelClearVolumeControl =
				new ControlJPanel( cvManager.getActiveChannelIndex(), cvManager.getClearVolumeRendererInterface() );
		this.add( panelClearVolumeControl, BorderLayout.SOUTH );

		final GenericClearVolumeGui< T > self = this;
		SwingUtilities.invokeLater( new Runnable() {

			@Override
			public void run() {
				self.revalidate();
			}
		} );
	}

	/**
	 * Call to retrieve the current app image. This will help you to circumvent
	 * the jogl icon stealing bullshit!
	 *
	 * @return
	 */
	public static Image getCurrentAppIcon() {
		final String os = System.getProperty( "os.name" ).toLowerCase();
		Image icon = null;
		if ( os.indexOf( "mac" ) >= 0 ) {
			icon = Application.getApplication().getDockIconImage();
		} else if ( os.indexOf( "win" ) >= 0 ) {
//			not yet clear
			icon = null;
		} else {
//			not yet clear
			icon = null;
		}
		return icon;
	}

	/**
	 * @param finalicon
	 */
	public static void setCurrentAppIcon( final Image finalicon ) {
		final String os = System.getProperty( "os.name" ).toLowerCase();

		if ( finalicon == null ) return;

		SwingUtilities.invokeLater( new Runnable() {
			@Override
			public void run() {
				if ( os.indexOf( "mac" ) >= 0 ) {
					Application.getApplication().setDockIconImage( finalicon );
				} else if ( os.indexOf( "win" ) >= 0 ) {
//					not yet clear
				} else {
//					not yet clear
				}
			}
		} );
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void actionPerformed( final ActionEvent e ) {

		if ( e.getSource().equals( buttonUpdateView ) || e.getActionCommand().equals( "UpdateView" ) ) {
			activateGuiValues();
			cvManager.updateView();
		} else if ( e.getSource().equals( buttonResetView ) ) {
			cvManager.setVoxelSize(
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.X ) ),
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.Y ) ),
					imgPlus.averageScale( imgPlus.dimensionIndex( Axes.Z ) ) );
			pushParamsToGui();
			cvManager.resetView();
		} else if ( e.getSource().equals( buttonToggleBox ) ) {
			cvManager.toggleBox();
		} else if ( e.getSource().equals( buttonToggleRecording ) ) {
			cvManager.toggleRecording();
		} else if ( e.getSource().equals( cbRenormalizeFrames ) ) {
			bDoRenormalize = cbRenormalizeFrames.isSelected();
			extractChannelsAtT( t );
			showExtractedChannels();
		}  else if ( e.getSource().equals( txtFps ) ) {
			try{
				fps = Integer.parseInt( txtFps.getText() );
			} catch(final NumberFormatException nfe) {
				//fps = fps;
			}
		} else if ( e.getSource().equals( buttonPlayTime ) ) {
			if ( threadLoopTime == null ) {
				setIcon( buttonPlayTime, "pause.gif", "X", Color.BLUE );
				threadLoopTime = new LoopThread();
				threadLoopTime.start();
			} else {
				threadLoopTime.endLooping();
				threadLoopTime = null;
				setIcon( buttonPlayTime, "play.gif", ">", Color.BLUE );
			}
		} else if ( e.getSource().equals( buttonCredits ) ) {
			new CreditsDialog( this );
		}

	}

	/**
	 * @see javax.swing.event.ChangeListener#stateChanged(javax.swing.event.ChangeEvent)
	 */
	@Override
	public void stateChanged( final ChangeEvent e ) {
		if ( e.getSource().equals( sliderTime ) ) {
			t = sliderTime.getValue();
			lblTime.setText( String.format( "t=%02d", ( t + 1 ) ) );

			extractChannelsAtT( t );
			showExtractedChannels();
		}
	}

	/**
	 * @param t
	 */
	public void extractChannelsAtT( final int t ) {
		final List< RandomAccessibleInterval< T >> newimages =
				new ArrayList< RandomAccessibleInterval< T >>();

		final int dC = imgPlus.dimensionIndex( Axes.CHANNEL );
		final int dT = imgPlus.dimensionIndex( Axes.TIME );

		final RandomAccessibleInterval< T > timePointToShow =
				Views.hyperSlice( imgPlus, dT, t );
		imgPlus.getColorTable( dC );
		if ( dC == -1 ) {
			newimages.add( timePointToShow );
		} else
			for ( int channel = 0; channel < timePointToShow.dimension( dC ); channel++ ) {
			final RandomAccessibleInterval< T > rai =
					Views.hyperSlice( timePointToShow, dC, channel );
			newimages.add( rai );
		}
		images = newimages;
	}

	/**
	 * Updates an validly initialized ClearVolume Manager so that he shows the
	 * data in local field <code>images</code>.
	 */
	private void showExtractedChannels() {
		cvManager.updateImages( images, bDoRenormalize );
	}

	/**
	 */
	private void setIcon(
			final JButton button,
			final String filename,
			final String altText,
			final Color altColor ) {
		try {
			URL iconURL = ClassLoader.getSystemResource( filename );
			if ( iconURL == null ) {
				iconURL = getClass().getClassLoader().getResource( filename );
			}
			final Image img = ImageIO.read( iconURL );
			button.setIcon( new ImageIcon( img.getScaledInstance(
					20,
					20,
					java.awt.Image.SCALE_SMOOTH ) ) );
		} catch ( final Exception e ) {
			e.printStackTrace();
			button.setText( altText );
			button.setForeground( altColor );
		}
	}

}
