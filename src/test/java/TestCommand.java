import net.imagej.ImageJ;
import org.scijava.command.Command;
import org.scijava.command.DynamicCommand;
import org.scijava.command.Interactive;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.widget.Button;
import java.awt.*;

@Plugin(type = Command.class, menuPath = "", initializer = "init")
public class TestCommand extends DynamicCommand implements Interactive
{

	@Parameter( label = "Text")
	String text = "Hello World!";

	@Parameter( label = "Print text", callback = "printText" )
	private Button action;

	public void run()
	{ }

	public void init()
	{ }

	private void printText()
	{
		System.out.println( text );
	}

	public static void main ( String... args )
	{
		final ImageJ ij = new ImageJ();
		ij.ui().showUI();

		// invoke the plugin
		ij.command().run( TestCommand.class, true );
	}
}