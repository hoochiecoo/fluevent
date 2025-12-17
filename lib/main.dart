class PixelPainter extends CustomPainter {
  final List<Offset> points;
  final int imageW;
  final int imageH;

  PixelPainter({
    required this.points,
    required this.imageW,
    required this.imageH,
  });

  @override
  void paint(Canvas canvas, Size size) {
    if (imageW == 0 || imageH == 0) return;

    // Камера почти всегда отдаёт landscape, экран — portrait
    final bool rotated = imageW > imageH;

    final double srcW = rotated ? imageH.toDouble() : imageW.toDouble();
    final double srcH = rotated ? imageW.toDouble() : imageH.toDouble();

    // === BoxFit.cover ===
    final double scale =
        (size.width / srcW).compareTo(size.height / srcH) > 0
            ? size.width / srcW
            : size.height / srcH;

    final double drawnW = srcW * scale;
    final double drawnH = srcH * scale;

    final double offsetX = (size.width - drawnW) / 2;
    final double offsetY = (size.height - drawnH) / 2;

    final paint = Paint()
      ..color = Colors.yellowAccent
      ..style = PaintingStyle.fill;

    for (final p in points) {
      double x = p.dx;
      double y = p.dy;

      // 🔄 поворот на 90°
      if (rotated) {
        final tmp = x;
        x = y;
        y = imageW - tmp;
      }

      final double px = x * scale + offsetX;
      final double py = y * scale + offsetY;

      // не рисуем за экраном
      if (px < 0 || py < 0 || px > size.width || py > size.height) continue;

      canvas.drawCircle(
        Offset(px, py),
        2,
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
